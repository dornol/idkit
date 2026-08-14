package io.github.dornol.idkit.spring.boot

import io.github.dornol.idkit.worker.WorkerIdLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class IdKitAutoConfigurationSupportTest {
    @Test
    fun `configured acquisition retries and preserves the first available worker`() {
        val properties = IdKitProperties().apply {
            workerCount = 3
            acquisitionAttempts = 2
            acquisitionRetryDelay = Duration.ZERO
        }
        val lease = testLease(workerId = 2)
        val calls = AtomicInteger()

        val acquired = IdKitAutoConfigurationSupport.acquireConfigured(
            properties = properties,
            backend = "test",
            tryAcquire = { workerId ->
                calls.incrementAndGet()
                if (workerId == 2) lease else null
            },
        )

        assertSame(lease, acquired)
        assertEquals(3, calls.get())
    }

    @Test
    fun `common validation rejects an invalid namespace`() {
        val properties = IdKitProperties().apply { leaseNamespace = "invalid-name" }

        assertThrows<IllegalArgumentException> {
            IdKitAutoConfigurationSupport.validateCommon(properties)
        }
    }

    @Test
    fun `common validation rejects a heartbeat interval that can outlive the ttl`() {
        val properties = IdKitProperties().apply {
            leaseTtl = Duration.ofSeconds(10)
            heartbeatInterval = Duration.ofSeconds(5)
            heartbeatFailureThreshold = 2
        }

        assertThrows<IllegalArgumentException> {
            IdKitAutoConfigurationSupport.validateCommon(properties)
        }
    }

    private fun testLease(workerId: Int): WorkerIdLease = object : WorkerIdLease {
        override val workerId: Int = workerId
        override val datacenterId: Int = 0
        override fun close() = Unit
    }
}
