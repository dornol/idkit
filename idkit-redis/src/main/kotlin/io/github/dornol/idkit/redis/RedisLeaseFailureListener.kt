package io.github.dornol.idkit.redis

/** Receives heartbeat failures and lease-loss events from a Redis-backed lease. */
fun interface RedisLeaseFailureListener {
    fun onFailure(workerId: Int, datacenterId: Int, cause: Throwable)
}
