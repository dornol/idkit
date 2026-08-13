package io.github.dornol.idkit.redis

interface RedisLeaseMetrics {
    fun acquired()
    fun acquisitionFailed()
    fun heartbeatSucceeded()
    fun heartbeatFailed()
    fun released()
    fun activeLeases(count: Int)
}

object NoopRedisLeaseMetrics : RedisLeaseMetrics {
    override fun acquired() = Unit
    override fun acquisitionFailed() = Unit
    override fun heartbeatSucceeded() = Unit
    override fun heartbeatFailed() = Unit
    override fun released() = Unit
    override fun activeLeases(count: Int) = Unit
}
