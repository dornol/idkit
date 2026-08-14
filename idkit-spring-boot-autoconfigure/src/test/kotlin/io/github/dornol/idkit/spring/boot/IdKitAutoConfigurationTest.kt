package io.github.dornol.idkit.spring.boot

import io.github.dornol.idkit.IdGenerator
import io.github.dornol.idkit.flake.FlakeIdGenerator
import io.github.dornol.idkit.worker.WorkerIdLease
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class IdKitAutoConfigurationTest {
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
}
