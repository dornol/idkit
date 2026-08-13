package io.github.dornol.idkit.jdbc

/** Receives heartbeat failures and lease-loss events from a JDBC-backed lease. */
fun interface JdbcLeaseFailureListener {
    fun onFailure(workerId: Int, datacenterId: Int, cause: Throwable)
}
