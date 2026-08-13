package io.github.dornol.idkit.jdbc

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MicrometerJdbcLeaseMetricsTest {
    @Test
    fun `records lease lifecycle counters and active gauge`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerJdbcLeaseMetrics(registry)

        metrics.acquired()
        metrics.heartbeatSucceeded()
        metrics.heartbeatFailed()
        metrics.released()
        metrics.acquisitionFailed()
        metrics.activeLeases(3)

        assertEquals(1.0, registry.get("idkit.jdbc.lease.acquired").counter().count())
        assertEquals(1.0, registry.get("idkit.jdbc.lease.acquisition.failed").counter().count())
        assertEquals(1.0, registry.get("idkit.jdbc.lease.heartbeat.succeeded").counter().count())
        assertEquals(1.0, registry.get("idkit.jdbc.lease.heartbeat.failed").counter().count())
        assertEquals(1.0, registry.get("idkit.jdbc.lease.released").counter().count())
        assertEquals(3.0, registry.get("idkit.jdbc.lease.active").gauge().value())
    }
}
