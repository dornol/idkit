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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdKitSpringBootIntegrationTest {
    private lateinit var postgres: GenericContainer<*>
    private lateinit var redis: GenericContainer<*>
    private lateinit var postgresDataSource: PGSimpleDataSource

    @BeforeAll
    fun startContainers() {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker is unavailable; skipping Spring Boot integration tests",
        )

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
            )
            .run { context ->
                assertTrue(context.getBean(IdGenerator::class.java).nextId() is Long)
                assertTrue(context.getBeansOfType(HealthIndicator::class.java).isEmpty())
            }
    }
}
