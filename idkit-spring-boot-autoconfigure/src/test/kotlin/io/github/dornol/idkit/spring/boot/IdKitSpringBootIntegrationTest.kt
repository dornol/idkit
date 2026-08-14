package io.github.dornol.idkit.spring.boot

import io.github.dornol.idkit.IdGenerator
import io.github.dornol.idkit.jdbc.JdbcWorkerIdLeaseStore
import io.github.dornol.idkit.redis.RedisWorkerIdLeaseStore
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource
import java.util.function.Supplier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdKitSpringBootIntegrationTest {
    private lateinit var postgres: GenericContainer<*>
    private lateinit var redis: GenericContainer<*>
    private lateinit var postgresDataSource: PGSimpleDataSource

    @BeforeAll
    fun startContainers() {
        val available = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        if (!available && System.getProperty("idkit.requireIntegrationTests").toBoolean()) {
            error("Docker is required for Spring Boot integration tests")
        }
        assumeTrue(available, "Docker is unavailable; skipping Spring Boot integration tests")

        postgres = GenericContainer(DockerImageName.parse("postgres:16-alpine"))
            .withEnv("POSTGRES_DB", "idkit")
            .withEnv("POSTGRES_USER", "idkit")
            .withEnv("POSTGRES_PASSWORD", "idkit")
            .withExposedPorts(5432)
        postgres.start()

        postgresDataSource = PGSimpleDataSource().apply {
            setServerNames(arrayOf(postgres.host))
            portNumbers = intArrayOf(postgres.getMappedPort(5432))
            databaseName = "idkit"
            user = "idkit"
            password = "idkit"
        }

        redis = GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
        redis.start()
    }

    @AfterAll
    fun stopContainers() {
        if (::postgres.isInitialized) postgres.stop()
        if (::redis.isInitialized) redis.stop()
    }

    @Test
    fun jdbcBackendCreatesGeneratorHealthAndMetrics() {
        val registry = SimpleMeterRegistry()
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    JdbcIdKitAutoConfiguration::class.java,
                    RedisIdKitAutoConfiguration::class.java,
                    JdbcMicrometerIdKitAutoConfiguration::class.java,
                    RedisMicrometerIdKitAutoConfiguration::class.java,
                    IdKitHealthAutoConfiguration::class.java,
                ),
            )
            .withBean(DataSource::class.java, Supplier { postgresDataSource })
            .withBean(MeterRegistry::class.java, Supplier { registry })
            .withPropertyValues(
                "idkit.backend=jdbc",
                "idkit.worker-count=4",
                "idkit.owner=spring-jdbc-test",
                "idkit.jdbc.auto-initialize=true",
                "idkit.jdbc.validate-schema=true",
                "idkit.jdbc.table-name=idkit_spring_jdbc_lease",
            )
            .run { context ->
                assertTrue(context.getBean(IdGenerator::class.java).nextId() is Long)
                assertNotNull(context.getBean(JdbcWorkerIdLeaseStore::class.java))
                assertTrue(context.getBeansOfType(RedisWorkerIdLeaseStore::class.java).isEmpty())
                assertNotNull(context.getBean(HealthIndicator::class.java))
                assertTrue(registry.meters.any { it.id.name.endsWith("acquired") })

                val lease = context.getBean(io.github.dornol.idkit.worker.WorkerIdLease::class.java)
                assertTrue(lease.isValid)
                lease.close()
                assertEquals("DOWN", context.getBean(HealthIndicator::class.java).health().status.code)
            }
    }

    @Test
    fun redisBackendCreatesGeneratorAndJdbcStaysInactive() {
        val registry = SimpleMeterRegistry()
        val uri = "redis://${redis.host}:${redis.getMappedPort(6379)}"
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    JdbcIdKitAutoConfiguration::class.java,
                    RedisIdKitAutoConfiguration::class.java,
                    JdbcMicrometerIdKitAutoConfiguration::class.java,
                    RedisMicrometerIdKitAutoConfiguration::class.java,
                    IdKitHealthAutoConfiguration::class.java,
                ),
            )
            .withBean(MeterRegistry::class.java, Supplier { registry })
            .withPropertyValues(
                "idkit.backend=redis",
                "idkit.worker-count=4",
                "idkit.owner=spring-redis-test",
                "idkit.redis.uri=$uri",
                "idkit.redis.key-prefix=test:idkit:spring",
            )
            .run { context ->
                assertTrue(context.getBean(IdGenerator::class.java).nextId() is Long)
                assertNotNull(context.getBean(RedisWorkerIdLeaseStore::class.java))
                assertTrue(context.getBeansOfType(JdbcWorkerIdLeaseStore::class.java).isEmpty())
                assertTrue(registry.meters.any { it.id.name.endsWith("acquired") })
                assertNotNull(context.getBean(HealthIndicator::class.java))
            }
    }

    @Test
    fun metricsAndHealthCanBeDisabled() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    JdbcIdKitAutoConfiguration::class.java,
                    JdbcMicrometerIdKitAutoConfiguration::class.java,
                    IdKitHealthAutoConfiguration::class.java,
                ),
            )
            .withBean(DataSource::class.java, Supplier { postgresDataSource })
            .withBean(MeterRegistry::class.java, Supplier { SimpleMeterRegistry() })
            .withPropertyValues(
                "idkit.backend=jdbc",
                "idkit.worker-count=2",
                "idkit.owner=spring-disabled-test",
                "idkit.jdbc.auto-initialize=true",
                "idkit.jdbc.table-name=idkit_spring_disabled_lease",
                "idkit.metrics.enabled=false",
                "idkit.health.enabled=false",
                "idkit.recovery.enabled=false",
            )
            .run { context ->
                assertTrue(context.getBean(IdGenerator::class.java).nextId() is Long)
                assertTrue(context.getBeansOfType(HealthIndicator::class.java).isEmpty())
                assertTrue(context.getBeansOfType(io.github.dornol.idkit.worker.LeaseRecoveryStatus::class.java).isEmpty())
            }
    }

    @Test
    fun jdbcSpringContextRecoversLeaseAndHealthAfterOwnershipLoss() {
        val registry = SimpleMeterRegistry()
        val tableName = "idkit_spring_recovery_lease"
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    JdbcIdKitAutoConfiguration::class.java,
                    JdbcMicrometerIdKitAutoConfiguration::class.java,
                    IdKitHealthAutoConfiguration::class.java,
                ),
            )
            .withBean(DataSource::class.java, Supplier { postgresDataSource })
            .withBean(MeterRegistry::class.java, Supplier { registry })
            .withPropertyValues(
                "idkit.backend=jdbc",
                "idkit.worker-count=2",
                "idkit.owner=spring-recovery-test",
                "idkit.lease-ttl=2s",
                "idkit.recovery.retry-delay=500ms",
                "idkit.jdbc.auto-initialize=true",
                "idkit.jdbc.validate-schema=true",
                "idkit.jdbc.table-name=$tableName",
            )
            .run { context ->
                val lease = context.getBean(io.github.dornol.idkit.worker.WorkerIdLease::class.java)
                val generator = context.getBean(IdGenerator::class.java)
                val health = context.getBean(HealthIndicator::class.java)
                assertEquals(0, lease.workerId)
                generator.nextId()

                postgresDataSource.connection.use { connection ->
                    connection.prepareStatement(
                        "UPDATE $tableName SET owner_token = 'foreign-owner' WHERE datacenter_id = 0 AND worker_id = 0",
                    ).use { it.executeUpdate() }
                }

                eventually { !lease.isValid }
                assertThrows<IllegalStateException> { generator.nextId() }
                assertEquals("DOWN", health.health().status.code)
                eventually { health.health().status.code == "UP" }

                val recoveredLease = context.getBean(io.github.dornol.idkit.worker.LeaseRecoveryStatus::class.java).currentLease
                assertEquals(1, recoveredLease.workerId)
                generator.nextId()
                assertTrue(registry.get("idkit.lease.recovery.attempted").counter().count() >= 1.0)
                assertTrue(registry.get("idkit.lease.recovery.succeeded").counter().count() >= 1.0)
            }

        postgresDataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE $tableName SET owner_token = NULL, lease_until = NULL WHERE datacenter_id = 0",
            ).use { it.executeUpdate() }
        }
    }

    private fun eventually(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue(condition(), "condition was not met before timeout")
    }

    @Test
    fun jdbcStartupRetriesWhenTheOnlyWorkerIsTemporarilyOccupied() {
        val tableName = "idkit_spring_retry_lease"
        val setupScheduler = Executors.newSingleThreadScheduledExecutor()
        val setupStore = JdbcWorkerIdLeaseStore(
            dataSource = postgresDataSource,
            scheduler = setupScheduler,
            tableName = tableName,
        )
        setupStore.initialize(workerCount = 1)
        val occupied = setupStore.tryAcquire(0, 0, "occupied", 5_000)!!
        setupScheduler.schedule({ occupied.close() }, 100, TimeUnit.MILLISECONDS)

        try {
            ApplicationContextRunner()
                .withConfiguration(
                    AutoConfigurations.of(
                        JdbcIdKitAutoConfiguration::class.java,
                    ),
                )
                .withBean(DataSource::class.java, Supplier { postgresDataSource })
                .withPropertyValues(
                    "idkit.backend=jdbc",
                    "idkit.worker-count=1",
                    "idkit.owner=spring-retry-test",
                    "idkit.jdbc.table-name=$tableName",
                    "idkit.jdbc.validate-schema=true",
                    "idkit.acquisition-attempts=4",
                    "idkit.acquisition-retry-delay=150ms",
                )
                .run { context ->
                    assertTrue(context.getBean(io.github.dornol.idkit.worker.WorkerIdLease::class.java).isValid)
                }
        } finally {
            setupStore.close()
            setupScheduler.shutdownNow()
        }
    }
}
