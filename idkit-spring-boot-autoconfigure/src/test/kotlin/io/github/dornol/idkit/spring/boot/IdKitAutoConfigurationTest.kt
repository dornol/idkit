package io.github.dornol.idkit.spring.boot

import io.github.dornol.idkit.IdGenerator
import io.github.dornol.idkit.flake.FlakeIdGenerator
import io.github.dornol.idkit.worker.WorkerIdLease
import org.springframework.boot.actuate.health.HealthIndicator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.function.Supplier

class IdKitAutoConfigurationTest {
    @Test
    fun `JDBC schema initialization is opt in by default`() {
        assertTrue(!IdKitProperties().jdbc.autoInitialize)
    }

    @Test
    fun `does not create a generator when backend is not configured`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    JdbcIdKitAutoConfiguration::class.java,
                    RedisIdKitAutoConfiguration::class.java,
                ),
            )
            .run { context ->
                assertTrue(context.getBeansOfType(IdGenerator::class.java).isEmpty())
            }
    }

    @Test
    fun `creates a custom flake layout from properties`() {
        val properties = IdKitProperties().apply {
            generator.type = IdKitProperties.Type.FLAKE
            generator.timestampBits = 42
            generator.datacenterIdBits = 4
            generator.workerIdBits = 6
        }
        val lease = object : WorkerIdLease {
            override val workerId = 3
            override val datacenterId = 2
            override fun close() = Unit
        }

        val generator = IdKitGeneratorFactory.create(lease, properties)

        assertTrue(generator is FlakeIdGenerator)
        assertTrue((generator as FlakeIdGenerator).sequenceBits == 11)
    }

    @Test
    fun `rejects an invalid flake layout before backend acquisition`() {
        val properties = IdKitProperties().apply {
            generator.type = IdKitProperties.Type.FLAKE
            generator.workerIdBits = 32
        }

        assertThrows<IllegalArgumentException> {
            IdKitGeneratorFactory.validate(properties)
        }
    }

    @Test
    fun `does not replace a user supplied health indicator`() {
        val custom = HealthIndicator { org.springframework.boot.actuate.health.Health.unknown().build() }
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdKitHealthAutoConfiguration::class.java))
            .withBean(WorkerIdLease::class.java, Supplier {
                object : WorkerIdLease {
                override val workerId = 1
                override val datacenterId = 2
                override fun close() = Unit
                }
            })
            .withBean("idKitHealthIndicator", HealthIndicator::class.java, Supplier { custom })
            .withPropertyValues("idkit.health.enabled=true")
            .run { context ->
                assertTrue(context.getBean(HealthIndicator::class.java) === custom)
            }
    }
}
