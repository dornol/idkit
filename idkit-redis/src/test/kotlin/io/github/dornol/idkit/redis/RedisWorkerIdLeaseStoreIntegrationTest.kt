package io.github.dornol.idkit.redis

import io.github.dornol.idkit.IdGenerator
import io.github.dornol.idkit.worker.LeasedIdGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
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
import java.util.concurrent.atomic.AtomicInteger
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
        if (!dockerAvailable && System.getProperty("idkit.requireIntegrationTests").toBoolean()) {
            error("Docker is required for Redis integration tests")
        }
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
    fun `lease becomes invalid when heartbeat scheduler is stopped before ttl expiry`() {
        val isolatedScheduler = Executors.newSingleThreadScheduledExecutor()
        val isolatedStore = RedisWorkerIdLeaseStore(
            connection.sync(),
            isolatedScheduler,
            keyPrefix = "test:deadline:worker",
        )
        val lease = isolatedStore.tryAcquire(0, 9, "deadline", 150)!!
        isolatedScheduler.shutdownNow()

        Thread.sleep(250)

        assertFalse(lease.isValid)
        lease.close()
        connection.sync().del("test:deadline:worker:0:9")
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
    fun `acquire retries until an occupied worker is released`() {
        val occupied = store.tryAcquire(0, 10, "occupied", 5_000)!!
        scheduler.schedule({ occupied.close() }, 100, TimeUnit.MILLISECONDS)

        val acquired = store.acquireAny(
            workerCount = 1,
            datacenterId = 10,
            owner = "retrying",
            ttlMillis = 2_000,
            acquisitionAttempts = 4,
            acquisitionRetryDelayMillis = 150,
        )

        assertEquals("retrying", store.inspect(0, 10).owner)
        acquired.close()
    }

    @Test
    fun `heartbeat tolerates one transient ownership failure when threshold is two`() {
        val key = "test:threshold:2:11:0"
        val heartbeatFailures = AtomicInteger()
        val heartbeatSuccesses = AtomicInteger()
        val resilientStore = RedisWorkerIdLeaseStore(
            connection.sync(),
            scheduler,
            keyPrefix = "test:threshold:2",
            heartbeatFailureThreshold = 2,
            metrics = object : RedisLeaseMetrics {
                override fun acquired() = Unit
                override fun acquisitionFailed() = Unit
                override fun heartbeatSucceeded() { heartbeatSuccesses.incrementAndGet() }
                override fun heartbeatFailed() { heartbeatFailures.incrementAndGet() }
                override fun released() = Unit
                override fun activeLeases(count: Int) = Unit
            },
        )
        val lease = resilientStore.tryAcquire(0, 11, "resilient", 900)!!
        val storedToken = connection.sync().get(key)!!
        connection.sync().set(key, "foreign")

        eventually { heartbeatFailures.get() == 1 }
        assertTrue(lease.isValid)

        connection.sync().set(key, storedToken)
        eventually { heartbeatSuccesses.get() >= 1 && lease.isValid }
        assertTrue(lease.isValid)
        lease.close()
        connection.sync().del(key, "test:threshold:2:fence:11:0")
    }

    @Test
    fun `release backend failure does not escape close and invalidates locally`() {
        val isolatedClient = RedisClient.create("redis://${redis.host}:${redis.getMappedPort(6379)}")
        val isolatedConnection = isolatedClient.connect()
        val isolatedScheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val isolatedStore = RedisWorkerIdLeaseStore(
                isolatedConnection.sync(),
                isolatedScheduler,
                keyPrefix = "test:release-failure",
            )
            val lease = isolatedStore.tryAcquire(0, 12, "release-failure", 5_000)!!
            isolatedConnection.close()

            assertDoesNotThrow { lease.close() }
            assertFalse(lease.isValid)
        } finally {
            isolatedScheduler.shutdownNow()
            isolatedClient.shutdown()
        }
    }

    @Test
    fun `scheduler registration failure rolls back the acquired Redis lease`() {
        val rejectedScheduler = Executors.newSingleThreadScheduledExecutor()
        rejectedScheduler.shutdownNow()
        val rejectedStore = RedisWorkerIdLeaseStore(
            connection.sync(),
            rejectedScheduler,
            keyPrefix = "test:scheduler-rejected",
        )

        assertThrows<RuntimeException> {
            rejectedStore.tryAcquire(0, 13, "scheduler-rejected", 5_000)
        }
        assertFalse(rejectedStore.inspect(0, 13).isHeld)
        connection.sync().del("test:scheduler-rejected:13:0", "test:scheduler-rejected:fence:13:0")
    }

    private fun eventually(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue(condition())
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
    fun `Redis fencing validator survives a concurrent token stress burst`() {
        val validator = RedisFencingTokenValidator(connection.sync(), "test:fencing-stress")
        val workers = Executors.newFixedThreadPool(16)
        try {
            val futures = (1L..256L).map { token ->
                workers.submit {
                    client.connect().use { ownedConnection ->
                        RedisFencingTokenValidator(ownedConnection.sync(), "test:fencing-stress")
                            .accept("orders", token)
                    }
                }
            }
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
            assertEquals(256L, validator.current("orders"))
        } finally {
            workers.shutdownNow()
            connection.sync().del("test:fencing-stress:orders")
        }
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
