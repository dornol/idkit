package io.github.dornol.idkit.spring.boot

import io.github.dornol.idkit.worker.WorkerIdLease
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

/** Shared, backend-independent bootstrap policy for the JDBC and Redis integrations. */
internal object IdKitAutoConfigurationSupport {
    private val simpleIdentifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun validateCommon(properties: IdKitProperties) {
        require(properties.workerCount > 0) { "idkit.worker-count must be > 0" }
        require(properties.workerId == null || properties.workerId in 0 until properties.workerCount) {
            "idkit.worker-id must be between 0 and worker-count - 1"
        }
        require(properties.datacenterId >= 0) { "idkit.datacenter-id must be >= 0" }
        require(properties.owner.isNotBlank()) { "idkit.owner must not be blank" }
        require(properties.leaseTtl.isPositiveDuration()) { "idkit.lease-ttl must be positive" }
        require(properties.acquisitionAttempts in 1..10) {
            "idkit.acquisition-attempts must be between 1 and 10"
        }
        require(!properties.acquisitionRetryDelay.isNegative) {
            "idkit.acquisition-retry-delay must not be negative"
        }
        require(properties.recovery.retryDelay.isPositiveDuration()) {
            "idkit.recovery.retry-delay must be positive"
        }
        require(!properties.recovery.retryJitter.isNegative) {
            "idkit.recovery.retry-jitter must not be negative"
        }
        require(properties.recovery.maxRetryDelay.isPositiveDuration()) {
            "idkit.recovery.max-retry-delay must be positive"
        }
        require(properties.recovery.maxRetryDelay >= properties.recovery.retryDelay) {
            "idkit.recovery.max-retry-delay must be >= retry-delay"
        }
        require(properties.heartbeatFailureThreshold in 1..2) {
            "idkit.heartbeat-failure-threshold must be between 1 and 2 so the lease fails before TTL expiry"
        }
        validateHeartbeatInterval(properties)
        require(properties.backendOperationTimeout.isPositive()) {
            "idkit.backend-operation-timeout must be positive"
        }
        require(!properties.startupJitter.isNegative) {
            "idkit.startup-jitter must not be negative"
        }
        validateLeaseNamespace(properties.leaseNamespace)
    }

    fun validateJdbc(properties: IdKitProperties) {
        require(!properties.jdbc.clockSkewAllowance.isNegative) {
            "idkit.jdbc.clock-skew-allowance must not be negative"
        }
        require(properties.jdbc.dataSourceBeanName?.isNotBlank() != false) {
            "idkit.jdbc.data-source-bean-name must not be blank"
        }
    }

    fun validateHeartbeatInterval(properties: IdKitProperties) {
        val interval = properties.heartbeatInterval ?: return
        require(interval.isPositiveDuration()) { "idkit.heartbeat-interval must be positive" }
        val intervalMillis = interval.toMillis()
        require(
            intervalMillis > 0 &&
                    runCatching {
                        Math.multiplyExact(intervalMillis, properties.heartbeatFailureThreshold.toLong())
                    }.getOrDefault(Long.MAX_VALUE) < properties.leaseTtl.toMillis(),
        ) {
            "idkit.heartbeat-interval and heartbeat-failure-threshold must detect lease loss before lease-ttl"
        }
    }

    fun validateLeaseNamespace(namespace: String?) {
        require(namespace?.matches(simpleIdentifier) != false) {
            "idkit.lease-namespace must be a simple identifier"
        }
    }

    fun sleepStartupJitter(maxDelayMillis: Long) {
        if (maxDelayMillis <= 0L) return
        val delay = ThreadLocalRandom.current().nextLong(maxDelayMillis + 1)
        sleep(delay, "Interrupted during idkit startup jitter")
    }

    fun sleepBeforeRetry(delayMillis: Long, backend: String) {
        if (delayMillis == 0L) return
        sleep(delayMillis, "Interrupted while retrying $backend worker lease acquisition")
    }

    fun workerIds(properties: IdKitProperties): IntRange =
        properties.workerId?.let { it..it } ?: (0 until properties.workerCount)

    fun acquireConfigured(
        properties: IdKitProperties,
        backend: String,
        tryAcquire: (Int) -> WorkerIdLease?,
        onFailure: (attempt: Int, retrying: Boolean, Throwable) -> Unit = { _, _, _ -> },
    ): WorkerIdLease {
        var lastFailure: Throwable? = null
        repeat(properties.acquisitionAttempts) { attempt ->
            try {
                for (workerId in workerIds(properties)) {
                    tryAcquire(workerId)?.let { return it }
                }
            } catch (failure: RuntimeException) {
                lastFailure = failure
                onFailure(attempt + 1, attempt + 1 < properties.acquisitionAttempts, failure)
            }
            if (attempt + 1 < properties.acquisitionAttempts) {
                sleepBeforeRetry(properties.acquisitionRetryDelay.toMillis(), backend)
            }
        }
        error(
            "No idkit $backend worker identity is available or the lease backend did not recover: " +
                    "workerCount=${properties.workerCount}, datacenterId=${properties.datacenterId}" +
                    (lastFailure?.let { "; lastFailure=${it.message}" } ?: ""),
        )
    }

    private fun Duration.isPositiveDuration(): Boolean = !isZero && !isNegative

    private fun sleep(delayMillis: Long, message: String) {
        try {
            Thread.sleep(delayMillis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException(message, interrupted)
        }
    }
}
