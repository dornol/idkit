package io.github.dornol.idkit.spring.boot

import io.github.dornol.idkit.worker.LeaseRecoveryMetrics
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicInteger

class MicrometerLeaseRecoveryMetrics(
    registry: MeterRegistry,
    prefix: String = "idkit.lease.recovery",
) : LeaseRecoveryMetrics {
    private val active = AtomicInteger()
    private val attempted = Counter.builder("$prefix.attempted").register(registry)
    private val succeeded = Counter.builder("$prefix.succeeded").register(registry)
    private val failed = Counter.builder("$prefix.failed").register(registry)

    init {
        Gauge.builder("$prefix.active", active) { it.get().toDouble() }.register(registry)
    }

    override fun recoveryAttempted() { attempted.increment() }
    override fun recoverySucceeded() { succeeded.increment() }
    override fun recoveryFailed() { failed.increment() }
    override fun recoveryActive(active: Boolean) { this.active.set(if (active) 1 else 0) }
}
