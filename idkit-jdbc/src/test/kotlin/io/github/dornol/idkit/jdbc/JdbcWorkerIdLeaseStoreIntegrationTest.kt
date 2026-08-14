package io.github.dornol.idkit.jdbc

import io.github.dornol.idkit.worker.LeasedIdGenerator
import io.github.dornol.idkit.worker.WorkerIdLease
import io.github.dornol.idkit.IdGenerator
import io.github.dornol.idkit.worker.FencedOperationResult
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.sql.SQLException
import javax.sql.DataSource
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcWorkerIdLeaseStoreIntegrationTest {
    private lateinit var postgres: GenericContainer<*>
    private lateinit var dataSource: PGSimpleDataSource
    private lateinit var scheduler: java.util.concurrent.ScheduledExecutorService
    private lateinit var store: JdbcWorkerIdLeaseStore

    @BeforeAll
    fun startDatabase() {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker is unavailable; skipping JDBC integration tests",
        )
        postgres = GenericContainer(DockerImageName.parse("postgres:16-alpine"))
            .withEnv("POSTGRES_DB", "idkit")
            .withEnv("POSTGRES_USER", "idkit")
            .withEnv("POSTGRES_PASSWORD", "idkit")
            .withExposedPorts(5432)
        postgres.start()

        dataSource = PGSimpleDataSource().apply {
            setServerNames(arrayOf(postgres.host))
            portNumbers = intArrayOf(postgres.getMappedPort(5432))
            databaseName = "idkit"
            user = "idkit"
            password = "idkit"
        }
        scheduler = Executors.newScheduledThreadPool(2)
        store = JdbcWorkerIdLeaseStore(
            dataSource = dataSource,
            scheduler = scheduler,
            dialect = JdbcLeaseDialect.POSTGRESQL,
            tableName = "idkit_test_worker_lease",
        )
    }

    @AfterAll
    fun stopDatabase() {
        if (::scheduler.isInitialized) scheduler.shutdownNow()
        if (::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `acquire is atomic and conflicting owner is rejected`() {
        val first = store.acquireAny(4, datacenterId = 1, owner = "node-a", ttlMillis = 2_000)
        assertEquals(0, first.workerId)
        assertNull(store.tryAcquire(0, 1, "node-b", 2_000))

        first.close()
        assertNotNull(store.tryAcquire(0, 1, "node-b", 2_000)?.also { it.close() })
    }

    @Test
    fun `heartbeat keeps lease alive beyond initial ttl`() {
        store.initialize(4, 2)
        val lease = store.tryAcquire(1, 2, "heartbeat", 900)
        assertNotNull(lease)
        Thread.sleep(1_500)
        assertTrue(lease!!.isValid)
        assertNull(store.tryAcquire(1, 2, "other", 900))
        lease.close()
    }

    @Test
    fun `inspect reports lease ownership and release`() {
        store.initialize(1, 7)
        val empty = store.inspect(0, 7)
        assertNotNull(empty)
        assertFalse(empty!!.isHeld)

        val lease = store.tryAcquire(0, 7, "inspect", 2_000)
        val held = store.inspect(0, 7)
        assertNotNull(held)
        assertTrue(held!!.isHeld)
        assertEquals("inspect", held.owner)
        assertNotNull(held.tokenFingerprint)
        assertTrue(held.remainingTtlMillis > 0)
        assertTrue(held.fencingToken > 0)
        lease!!.close()

        assertFalse(store.inspect(0, 7)!!.isHeld)
    }

    @Test
    fun `heartbeat failure is reported once`() {
        store.initialize(1, 8)
        val failures = CopyOnWriteArrayList<Throwable>()
        val failed = AtomicBoolean(false)
        val failingStore = JdbcWorkerIdLeaseStore(
            dataSource = dataSource.withConnectionFailure(failed),
            scheduler = scheduler,
            dialect = JdbcLeaseDialect.POSTGRESQL,
            tableName = "idkit_test_worker_lease",
            failureListener = JdbcLeaseFailureListener { _, _, cause -> failures += cause },
        )
        val lease = failingStore.tryAcquire(0, 8, "callback", 300)!!
        failed.set(true)
        eventually { failures.size == 1 }
        assertFalse(lease.isValid)
        Thread.sleep(150)
        assertEquals(1, failures.size)
        lease.close()
    }

    @Test
    fun `closing store releases all active leases`() {
        store.initialize(1, 9)
        val lease = store.tryAcquire(0, 9, "shutdown", 30_000)
        assertNotNull(lease)
        assertTrue(store.inspect(0, 9)!!.isHeld)

        store.close()

        assertFalse(store.inspect(0, 9)!!.isHeld)
        assertFalse(lease!!.isValid)
    }

    @Test
    fun `lease can be reacquired after explicit release`() {
        store.initialize(4, 3)
        val lease = store.tryAcquire(2, 3, "short-lived", 300)
        assertNotNull(lease)
        lease!!.close()
        val reacquired = store.tryAcquire(2, 3, "new-owner", 2_000)
        assertNotNull(reacquired)
        reacquired?.close()
    }

    @Test
    fun `heartbeat failure invalidates lease and generator fails closed`() {
        store.initialize(1, 4)
        val failed = AtomicBoolean(false)
        val failingDataSource = dataSource.withConnectionFailure(failed)
        val failingStore = JdbcWorkerIdLeaseStore(
            dataSource = failingDataSource,
            scheduler = scheduler,
            dialect = JdbcLeaseDialect.POSTGRESQL,
            tableName = "idkit_test_worker_lease",
        )
        val lease = failingStore.tryAcquire(0, 4, "failure", 300)
        assertNotNull(lease)
        failed.set(true)

        eventually { !lease!!.isValid }
        val generator = LeasedIdGenerator(object : IdGenerator<Long> {
            override fun nextId() = 1L
        }, lease!!)
        assertThrows<IllegalStateException> { generator.nextId() }
        lease.close()
    }

    @Test
    fun `expired lease can be taken over and stale owner cannot release the new lease`() {
        store.initialize(1, 5)
        val original = store.tryAcquire(0, 5, "original", 30_000)
        assertNotNull(original)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE idkit_test_worker_lease SET lease_until = 0 WHERE datacenter_id = 5 AND worker_id = 0",
            ).use { it.executeUpdate() }
        }

        val replacement = store.tryAcquire(0, 5, "replacement", 30_000)
        assertNotNull(replacement)
        original!!.close()
        assertNull(store.tryAcquire(0, 5, "third-owner", 30_000))
        replacement!!.close()
    }

    @Test
    fun `concurrent acquisition assigns each worker to at most one owner`() {
        val workerCount = 8
        val owners = Executors.newFixedThreadPool(workerCount)
        try {
            store.initialize(workerCount, 6)
            val futures: List<Future<WorkerIdLease?>> = (0 until workerCount * 2).map { index ->
                owners.submit<WorkerIdLease?> {
                    runCatching { store.acquireAny(workerCount, 6, "concurrent-$index", 30_000) }.getOrNull()
                }
            }
            val leases = futures.mapNotNull { it.get(10, TimeUnit.SECONDS) }
            assertEquals(workerCount, leases.size)
            assertEquals(workerCount, leases.map { it.workerId }.toSet().size)
            leases.forEach { it.close() }
        } finally {
            owners.shutdownNow()
        }
    }

    @Test
    fun `custom table name rejects unsafe SQL identifiers`() {
        assertThrows<IllegalArgumentException> {
            JdbcWorkerIdLeaseStore(dataSource, scheduler, tableName = "worker; DROP TABLE x")
        }
    }

    @Test
    fun `JDBC fencing validator rejects stale tokens atomically`() {
        val validator = JdbcFencingTokenValidator(dataSource, tableName = "idkit_test_fencing_token")
        validator.initialize()

        assertTrue(validator.accept("orders", 10))
        assertFalse(validator.accept("orders", 9))
        assertTrue(validator.accept("orders", 11))
        assertEquals(11L, validator.current("orders"))
    }

    @Test
    fun `JDBC fencing validator accepts an identical first token only once under contention`() {
        val validator = JdbcFencingTokenValidator(dataSource, tableName = "idkit_test_fencing_contention")
        validator.initialize()
        val workers = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..32).map {
                workers.submit(Callable { validator.accept("same-token", 7) })
            }
            val accepted = futures.count { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, accepted)
            assertEquals(7L, validator.current("same-token"))
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `JDBC fenced operation rolls back stale side effects`() {
        val executor = JdbcFencedOperationExecutor(dataSource, tableName = "idkit_test_fenced_ops")
        executor.initialize()
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS idkit_test_fenced_value (value INT NOT NULL)") }
            connection.createStatement().use { it.execute("DELETE FROM idkit_test_fenced_value") }
        }

        assertEquals(FencedOperationResult.APPLIED, executor.executeWithConnection("orders", 10) { connection ->
            connection.prepareStatement("INSERT INTO idkit_test_fenced_value (value) VALUES (?)").use { it.setInt(1, 10); it.executeUpdate() }
        })
        assertEquals(FencedOperationResult.REJECTED_STALE, executor.execute("orders", 9) { error("stale operation ran") })
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM idkit_test_fenced_value").use { result ->
                    result.next(); assertEquals(1, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun `JDBC fenced operation handles concurrent first resource creation`() {
        val executor = JdbcFencedOperationExecutor(dataSource, tableName = "idkit_test_fenced_race")
        executor.initialize()
        val workers = Executors.newFixedThreadPool(8)
        try {
            val futures = (1L..32L).map { token ->
                workers.submit(Callable {
                    executor.execute("race", token) { /* transaction boundary is the assertion */ }
                })
            }
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertTrue(results.all { it == FencedOperationResult.APPLIED || it == FencedOperationResult.REJECTED_STALE })
            assertEquals(32L, JdbcFencingTokenValidator(dataSource, tableName = "idkit_test_fenced_race").current("race"))
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `JDBC fencing token rolls back when side effect fails`() {
        val executor = JdbcFencedOperationExecutor(dataSource, tableName = "idkit_test_fenced_rollback")
        executor.initialize()
        assertThrows<IllegalStateException> {
            executor.execute("rollback", 10) { throw IllegalStateException("side effect failed") }
        }
        assertEquals(FencedOperationResult.APPLIED, executor.execute("rollback", 10) { })
    }

    private fun eventually(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue(condition())
    }

    private fun DataSource.withConnectionFailure(failed: AtomicBoolean): DataSource {
        val delegate = this
        return Proxy.newProxyInstance(
            DataSource::class.java.classLoader,
            arrayOf(DataSource::class.java),
        ) { _, method, args ->
            if (method.name == "getConnection" && failed.get()) {
                throw SQLException("simulated database outage")
            }
            method.invoke(delegate, *(args ?: emptyArray()))
        } as DataSource
    }
}
