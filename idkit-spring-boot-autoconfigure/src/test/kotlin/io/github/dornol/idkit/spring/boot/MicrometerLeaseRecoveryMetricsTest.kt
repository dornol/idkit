package io.github.dornol.idkit.spring.boot

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MicrometerLeaseRecoveryMetricsTest {
    @Test
    fun `records recovery counters and active gauge`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerLeaseRecoveryMetrics(registry, "test.recovery")

        metrics.recoveryAttempted()
        metrics.recoverySucceeded()
        metrics.recoveryFailed()
        metrics.recoveryActive(true)

        assertEquals(1.0, registry.get("test.recovery.attempted").counter().count())
        assertEquals(1.0, registry.get("test.recovery.succeeded").counter().count())
        assertEquals(1.0, registry.get("test.recovery.failed").counter().count())
        assertEquals(1.0, registry.get("test.recovery.active").gauge().value())

        metrics.recoveryActive(false)
        assertEquals(0.0, registry.get("test.recovery.active").gauge().value())
    }
}
