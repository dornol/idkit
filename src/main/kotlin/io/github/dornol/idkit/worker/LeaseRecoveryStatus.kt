package io.github.dornol.idkit.worker

/** Exposes the current lease selected by an automatically recovering generator. */
interface LeaseRecoveryStatus {
    val currentLease: WorkerIdLease
    val isRecovering: Boolean
    val lastRecoveryFailure: Throwable?
    val recoveryAttempts: Long
    val recoveryFailures: Long
}
