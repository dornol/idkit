package io.github.dornol.idkit.jdbc

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicInteger

/** Optional Micrometer adapter. Add micrometer-core to the application runtime classpath. */
class MicrometerJdbcLeaseMetrics(
    registry: MeterRegistry,
    prefix: String = "idkit.jdbc.lease",
) : JdbcLeaseMetrics {
    private val active = AtomicInteger()
    private val acquired = Counter.builder("$prefix.acquired").register(registry)
    private val acquisitionFailed = Counter.builder("$prefix.acquisition.failed").register(registry)
    private val heartbeatSucceeded = Counter.builder("$prefix.heartbeat.succeeded").register(registry)
    private val heartbeatFailed = Counter.builder("$prefix.heartbeat.failed").register(registry)
    private val released = Counter.builder("$prefix.released").register(registry)

    init {
        Gauge.builder("$prefix.active", active) { it.get().toDouble() }.register(registry)
    }

    override fun acquired() { active.incrementAndGet(); acquired.increment() }
    override fun acquisitionFailed() { acquisitionFailed.increment() }
    override fun heartbeatSucceeded() { heartbeatSucceeded.increment() }
    override fun heartbeatFailed() { heartbeatFailed.increment() }
    override fun released() { active.updateAndGet { (it - 1).coerceAtLeast(0) }; released.increment() }
    override fun activeLeases(count: Int) { active.set(count.coerceAtLeast(0)) }
}
