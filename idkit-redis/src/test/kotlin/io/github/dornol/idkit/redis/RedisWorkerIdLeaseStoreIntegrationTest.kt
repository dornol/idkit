package io.github.dornol.idkit.redis

import io.github.dornol.idkit.IdGenerator
import io.github.dornol.idkit.worker.LeasedIdGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import io.lettuce.core.RedisClient

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisWorkerIdLeaseStoreIntegrationTest {
    private lateinit var redis: GenericContainer<*>
    private lateinit var client: RedisClient
    private lateinit var connection: io.lettuce.core.api.StatefulRedisConnection<String, String>
    private lateinit var scheduler: java.util.concurrent.ScheduledExecutorService
    private lateinit var store: RedisWorkerIdLeaseStore

    @BeforeAll
    fun startRedis() {
        val dockerAvailable = runCatching {
            org.testcontainers.DockerClientFactory.instance().isDockerAvailable
        }.getOrDefault(false)
        assumeTrue(dockerAvailable, "Docker is unavailable; skipping Redis integration tests")

        redis = GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
        redis.start()
        client = RedisClient.create("redis://${redis.host}:${redis.getMappedPort(6379)}")
        connection = client.connect()
        scheduler = Executors.newScheduledThreadPool(2)
        store = RedisWorkerIdLeaseStore(connection.sync(), scheduler, keyPrefix = "test:idkit:worker")
    }

    @AfterAll
    fun stopRedis() {
        if (::scheduler.isInitialized) scheduler.shutdownNow()
        if (::connection.isInitialized) connection.close()
        if (::client.isInitialized) client.shutdown()
        if (::redis.isInitialized) redis.stop()
    }

    @Test
    fun `acquire is atomic and conflicting worker identity is rejected`() {
        val first = store.acquireAny(workerCount = 4, datacenterId = 1, owner = "node-a", ttlMillis = 2_000)
        assertEquals(0, first.workerId)

        val second = store.tryAcquire(0, 1, "node-b", 2_000)
        assertTrue(second == null)

        first.close()
        val afterRelease = store.tryAcquire(0, 1, "node-b", 2_000)
        assertNotNull(afterRelease)
        afterRelease?.close()
    }

    @Test
    fun `heartbeat keeps lease alive beyond its initial ttl`() {
        val lease = store.tryAcquire(2, 0, "node-heartbeat", 900)
        assertNotNull(lease)

        Thread.sleep(1_500)

        assertTrue(lease!!.isValid)
        val competing = store.tryAcquire(2, 0, "node-other", 900)
        assertTrue(competing == null)
        lease.close()
    }

    @Test
    fun `foreign owner cannot release an existing lease`() {
        val lease = store.tryAcquire(3, 0, "node-owner", 2_000)
        assertNotNull(lease)

        val commands = connection.sync()
        commands.set("test:idkit:worker:0:3", "foreign-owner")
        lease!!.close()

        assertEquals("foreign-owner", commands.get("test:idkit:worker:0:3"))
        commands.del("test:idkit:worker:0:3")
    }

    @Test
    fun `heartbeat loss invalidates lease and leased generator fails closed`() {
        val lease = store.tryAcquire(1, 0, "node-fail-closed", 900)
        assertNotNull(lease)
        val generator = LeasedIdGenerator(object : IdGenerator<Long> {
            override fun nextId(): Long = 123L
        }, lease!!)
        assertEquals(123L, generator.nextId())

        connection.sync().set("test:idkit:worker:0:1", "foreign-owner")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (lease.isValid && System.nanoTime() < deadline) Thread.sleep(50)

        assertFalse(lease.isValid)
        assertThrows<IllegalStateException> { generator.nextId() }
        connection.sync().del("test:idkit:worker:0:1")
    }

    @Test
    fun `inspect reports ttl and shutdown releases active lease`() {
        val lease = store.tryAcquire(0, 2, "inspect", 5_000)
        assertNotNull(lease)
        val held = store.inspect(0, 2)
        assertTrue(held.isHeld)
        assertEquals("inspect", held.owner)
        assertNotNull(held.tokenFingerprint)
        assertTrue(held.fencingToken > 0)
        assertTrue(held.remainingTtlMillis > 0)

        store.close()

        assertFalse(store.inspect(0, 2).isHeld)
        assertFalse(lease!!.isValid)
    }

    @Test
    fun `heartbeat failure invokes callback once`() {
        val failures = CopyOnWriteArrayList<Throwable>()
        val callbackStore = RedisWorkerIdLeaseStore(
            connection.sync(),
            scheduler,
            keyPrefix = "test:callback:worker",
            failureListener = RedisLeaseFailureListener { _, _, cause -> failures += cause },
        )
        val lease = callbackStore.tryAcquire(0, 0, "callback", 300)!!
        connection.sync().set("test:callback:worker:0:0", "foreign")

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (failures.isEmpty() && System.nanoTime() < deadline) Thread.sleep(25)
        assertFalse(lease.isValid)
        assertEquals(1, failures.size)
        lease.close()
        connection.sync().del("test:callback:worker:0:0")
    }

    @Test
    fun `Redis fencing validator rejects stale tokens atomically`() {
        val validator = RedisFencingTokenValidator(connection.sync(), "test:fencing")

        assertTrue(validator.accept("orders", 10))
        assertFalse(validator.accept("orders", 9))
        assertTrue(validator.accept("orders", 11))
        assertEquals(11L, validator.current("orders"))
        connection.sync().del("test:fencing:orders")
    }

    @Test
    fun `Redis fenced script rejects stale side effects atomically`() {
        val executor = RedisFencedScriptExecutor(connection.sync(), "test:fenced-script")
        val operation = "redis.call('set', KEYS[2], ARGV[2])"

        assertEquals(
            io.github.dornol.idkit.worker.FencedOperationResult.APPLIED,
            executor.execute("orders", 10, operation, arrayOf("test:fenced-script:value"), arrayOf("value-10")),
        )
        assertEquals(
            io.github.dornol.idkit.worker.FencedOperationResult.REJECTED_STALE,
            executor.execute("orders", 9, operation, arrayOf("test:fenced-script:value"), arrayOf("value-9")),
        )
        assertEquals("value-10", connection.sync().get("test:fenced-script:value"))
        connection.sync().del("test:fenced-script:orders", "test:fenced-script:value")
    }

    @Test
    fun `Redis fenced script does not advance token when operation script fails`() {
        val executor = RedisFencedScriptExecutor(connection.sync(), "test:fenced-error")
        assertThrows<RuntimeException> {
            executor.execute("orders", 10, "redis.call('does-not-exist', KEYS[2]); return 1", arrayOf("test:fenced-error:value"))
        }
        assertEquals(null, connection.sync().get("test:fenced-error:orders"))
    }

    @Test
    fun `Redis fenced scripts serialize concurrent token updates`() {
        val workers = Executors.newFixedThreadPool(8)
        try {
            val futures = (1L..32L).map { token ->
                workers.submit {
                    client.connect().use { ownedConnection ->
                        RedisFencedScriptExecutor(ownedConnection.sync(), "test:fenced-concurrent")
                            .execute("orders", token, "local applied = 1")
                    }
                }
            }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals("32", connection.sync().get("test:fenced-concurrent:orders"))
            connection.sync().del("test:fenced-concurrent:orders")
        } finally {
            workers.shutdownNow()
        }
    }
}
