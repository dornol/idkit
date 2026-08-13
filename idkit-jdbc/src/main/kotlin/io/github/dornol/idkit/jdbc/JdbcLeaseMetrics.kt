package io.github.dornol.idkit.jdbc

/** Metrics sink kept dependency-free for the core JDBC lease implementation. */
interface JdbcLeaseMetrics {
    fun acquired()
    fun acquisitionFailed()
    fun heartbeatSucceeded()
    fun heartbeatFailed()
    fun released()
    fun activeLeases(count: Int)
}

object NoopJdbcLeaseMetrics : JdbcLeaseMetrics {
    override fun acquired() = Unit
    override fun acquisitionFailed() = Unit
    override fun heartbeatSucceeded() = Unit
    override fun heartbeatFailed() = Unit
    override fun released() = Unit
    override fun activeLeases(count: Int) = Unit
}
