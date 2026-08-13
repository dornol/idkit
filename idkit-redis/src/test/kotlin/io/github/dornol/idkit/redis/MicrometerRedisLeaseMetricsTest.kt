package io.github.dornol.idkit.redis

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MicrometerRedisLeaseMetricsTest {
    @Test
    fun `records lease lifecycle counters and active gauge`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerRedisLeaseMetrics(registry)

        metrics.acquired()
        metrics.heartbeatSucceeded()
        metrics.heartbeatFailed()
        metrics.released()
        metrics.acquisitionFailed()
        metrics.activeLeases(3)

        assertEquals(1.0, registry.get("idkit.redis.lease.acquired").counter().count())
        assertEquals(1.0, registry.get("idkit.redis.lease.acquisition.failed").counter().count())
        assertEquals(1.0, registry.get("idkit.redis.lease.heartbeat.succeeded").counter().count())
        assertEquals(1.0, registry.get("idkit.redis.lease.heartbeat.failed").counter().count())
        assertEquals(1.0, registry.get("idkit.redis.lease.released").counter().count())
        assertEquals(3.0, registry.get("idkit.redis.lease.active").gauge().value())
    }
}
