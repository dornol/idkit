package io.github.dornol.idkit.worker

/** Result of an atomic fencing check plus downstream operation. */
enum class FencedOperationResult {
    APPLIED,
    REJECTED_STALE,
}

/** Common contract for adapters that bind fencing validation to a side effect atomically. */
fun interface FencedOperationExecutor {
    fun execute(resource: String, fencingToken: Long, operation: () -> Unit): FencedOperationResult
}

fun FencedOperationExecutor.requireApplied(
    resource: String,
    fencingToken: Long,
    operation: () -> Unit,
) {
    if (execute(resource, fencingToken, operation) == FencedOperationResult.REJECTED_STALE) {
        throw StaleFencingTokenException(resource, fencingToken, null)
    }
}
