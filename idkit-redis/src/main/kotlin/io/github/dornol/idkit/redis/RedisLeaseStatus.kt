package io.github.dornol.idkit.redis

/** A point-in-time view of one Redis worker lease key. */
data class RedisLeaseStatus(
    val workerId: Int,
    val datacenterId: Int,
    /** Owner name only; the random token is intentionally never exposed. */
    val owner: String?,
    val tokenFingerprint: String?,
    val fencingToken: Long,
    val remainingTtlMillis: Long,
) {
    val isHeld: Boolean
        get() = owner != null && remainingTtlMillis > 0
}
