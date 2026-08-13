package io.github.dornol.idkit.jdbc

/** A point-in-time view of one JDBC worker lease row. */
data class JdbcLeaseStatus(
    val workerId: Int,
    val datacenterId: Int,
    /** Owner name only; the random token is intentionally never exposed. */
    val owner: String?,
    val tokenFingerprint: String?,
    val fencingToken: Long,
    val leaseUntilMillis: Long?,
    val observedAtMillis: Long,
) {
    val isHeld: Boolean
        get() = owner != null && leaseUntilMillis != null && leaseUntilMillis > observedAtMillis

    val remainingTtlMillis: Long
        get() = ((leaseUntilMillis ?: 0L) - observedAtMillis).coerceAtLeast(0L)
}
