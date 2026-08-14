package io.github.dornol.idkit.worker

/** Optional metrics sink for automatic lease recovery. */
interface LeaseRecoveryMetrics {
    fun recoveryAttempted()
    fun recoverySucceeded()
    fun recoveryFailed()
    fun recoveryActive(active: Boolean)
}

object NoopLeaseRecoveryMetrics : LeaseRecoveryMetrics {
    override fun recoveryAttempted() = Unit
    override fun recoverySucceeded() = Unit
    override fun recoveryFailed() = Unit
    override fun recoveryActive(active: Boolean) = Unit
}
