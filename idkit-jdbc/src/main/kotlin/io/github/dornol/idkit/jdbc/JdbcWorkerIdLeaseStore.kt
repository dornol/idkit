package io.github.dornol.idkit.jdbc

import io.github.dornol.idkit.worker.WorkerIdLease
import io.github.dornol.idkit.worker.WorkerIdLeaseStore
import io.github.dornol.idkit.worker.LeaseClock
import io.github.dornol.idkit.worker.SystemLeaseClock
import javax.sql.DataSource
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JDBC-backed worker leases. ID generation never queries the database; only acquisition and the
 * background heartbeat use JDBC. Lease rows are pre-created, avoiding database-specific upsert
 * logic in the hot path.
 */
class JdbcWorkerIdLeaseStore(
    private val dataSource: DataSource,
    private val scheduler: ScheduledExecutorService,
    private val dialect: JdbcLeaseDialect = JdbcLeaseDialect.POSTGRESQL,
    private val tableName: String = "idkit_worker_lease",
    private val failureListener: JdbcLeaseFailureListener = JdbcLeaseFailureListener { _, _, _ -> },
    private val metrics: JdbcLeaseMetrics = NoopJdbcLeaseMetrics,
    private val clock: LeaseClock = SystemLeaseClock,
    private val heartbeatFailureThreshold: Int = 1,
    private val clockSkewAllowanceMillis: Long = 1_000L,
    private val statementTimeoutSeconds: Int = 5,
    private val heartbeatIntervalMillis: Long? = null,
) : WorkerIdLeaseStore, AutoCloseable {

    private val activeLeases = ConcurrentHashMap.newKeySet<JdbcWorkerIdLease>()
    private val closed = AtomicBoolean(false)
    /** Prevents a wall-clock rollback from extending a locally held lease. */
    private val effectiveClock = object : LeaseClock {
        private val last = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
        override fun millis(): Long = last.updateAndGet { maxOf(it, clock.millis()) }
    }

    init {
        requireValidTableName(tableName)
        require(heartbeatFailureThreshold in 1..2) {
            "heartbeatFailureThreshold must be between 1 and 2 so the lease fails before TTL expiry"
        }
        require(clockSkewAllowanceMillis >= 0) { "clockSkewAllowanceMillis must be >= 0" }
        require(statementTimeoutSeconds >= 0) { "statementTimeoutSeconds must be >= 0" }
        require(heartbeatIntervalMillis == null || heartbeatIntervalMillis > 0) {
            "heartbeatIntervalMillis must be > 0 when specified"
        }
    }

    /**
     * Verifies that the lease table, fencing column, and requested worker slots already exist.
     * This performs no DDL and is useful when schema changes are managed by Flyway/Liquibase.
     */
    fun validateSchema(workerCount: Int, datacenterId: Int = 0) {
        ensureOpen()
        require(workerCount > 0) { "workerCount must be > 0" }
        require(datacenterId >= 0) { "datacenterId must be >= 0" }
        dataSource.connection.use { connection ->
            connection.prepareLeaseStatement("SELECT fencing_token FROM $tableName WHERE 1 = 0").use {
                it.executeQuery().close()
            }
            connection.prepareLeaseStatement(
                "SELECT COUNT(*) FROM $tableName WHERE datacenter_id = ? AND worker_id >= 0 AND worker_id < ?",
            ).use { statement ->
                statement.setInt(1, datacenterId)
                statement.setInt(2, workerCount)
                statement.executeQuery().use { result ->
                    result.next()
                    val actual = result.getLong(1)
                    check(actual == workerCount.toLong()) {
                        "Expected $workerCount worker rows for datacenterId=$datacenterId, found $actual"
                    }
                }
            }
        }
    }

    /**
     * Returns reviewed SQL statements for a migration tool to apply. Values are validated and
     * rendered as numeric literals; the returned statements are not executed by this method.
     */
    fun migrationSql(workerCount: Int, datacenterId: Int = 0): List<String> {
        require(workerCount > 0) { "workerCount must be > 0" }
        require(datacenterId >= 0) { "datacenterId must be >= 0" }
        val create = if (dialect === JdbcLeaseDialect.MSSQL || dialect === JdbcLeaseDialect.ORACLE) {
            dialect.createTableSql.format(tableName, tableName, "${tableName}_pk")
        } else {
            dialect.createTableSql.format(tableName)
        }
        val statements = mutableListOf(create)
        dialect.addFencingTokenSql(tableName).takeIf { it.isNotBlank() }?.let(statements::add)
        repeat(workerCount) { workerId ->
            statements += dialect.insertIfAbsentSql(tableName)
                .replaceFirst("?", datacenterId.toString())
                .replaceFirst("?", workerId.toString())
        }
        return statements
    }

    fun initialize(workerCount: Int, datacenterId: Int = 0) {
        ensureOpen()
        require(workerCount > 0) { "workerCount must be > 0" }
        require(datacenterId >= 0) { "datacenterId must be >= 0" }
        dataSource.connection.use { connection ->
            val createSql = if (dialect === JdbcLeaseDialect.MSSQL || dialect === JdbcLeaseDialect.ORACLE) {
                dialect.createTableSql.format(tableName, tableName, "${tableName}_pk")
            } else {
                dialect.createTableSql.format(tableName)
            }
            connection.createStatement().use { it.execute(createSql) }
            connection.createStatement().use { statement ->
                dialect.addFencingTokenSql(tableName).takeIf { it.isNotBlank() }?.let {
                    try {
                        statement.execute(it)
                    } catch (failure: java.sql.SQLException) {
                        if (!dialect.isDuplicateFencingColumn(failure)) throw failure
                    }
                }
            }
            connection.autoCommit = false
            try {
                connection.prepareLeaseStatement(dialect.insertIfAbsentSql(tableName)).use { statement ->
                    for (workerId in 0 until workerCount) {
                        statement.setInt(1, datacenterId)
                        statement.setInt(2, workerId)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
            } catch (ex: Exception) {
                connection.rollback()
                throw ex
            }
        }
    }

    /** Reads one lease row for diagnostics; it does not acquire or renew the lease. */
    fun inspect(workerId: Int, datacenterId: Int): JdbcLeaseStatus? {
        require(workerId >= 0) { "workerId must be >= 0" }
        require(datacenterId >= 0) { "datacenterId must be >= 0" }
        val observedAt = effectiveClock.millis()
        dataSource.connection.use { connection ->
            connection.prepareLeaseStatement(
                "SELECT owner_token, lease_until, fencing_token FROM $tableName WHERE datacenter_id = ? AND worker_id = ?",
            ).use { statement ->
                statement.setInt(1, datacenterId)
                statement.setInt(2, workerId)
                statement.executeQuery().use { result ->
                    if (!result.next()) return null
                    val leaseUntil = result.getLong(2).let { if (result.wasNull()) null else it }
                    val fencingToken = result.getLong(3).let { if (result.wasNull()) 0L else it }
                    val rawToken = result.getString(1)
                    return JdbcLeaseStatus(
                        workerId = workerId,
                        datacenterId = datacenterId,
                        owner = rawToken?.substringBefore(':')?.takeIf { it.isNotEmpty() },
                        tokenFingerprint = rawToken?.let { Integer.toHexString(it.hashCode()) },
                        fencingToken = fencingToken,
                        leaseUntilMillis = leaseUntil,
                        observedAtMillis = observedAt,
                    )
                }
            }
        }
    }

    override fun tryAcquire(workerId: Int, datacenterId: Int, owner: String, ttlMillis: Long): WorkerIdLease? {
        ensureOpen()
        require(workerId >= 0) { "workerId must be >= 0" }
        require(datacenterId >= 0) { "datacenterId must be >= 0" }
        require(owner.isNotBlank()) { "owner must not be blank" }
        require(ttlMillis > 0) { "ttlMillis must be > 0" }
        val token = "$owner:${UUID.randomUUID()}"
        val now = effectiveClock.millis()
        val until = Math.addExact(now, ttlMillis)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                var currentFencingToken = 0L
                val available = connection.prepareLeaseStatement(
                    "SELECT owner_token, lease_until, fencing_token FROM ${dialect.fromTableSql(tableName)} " +
                            "WHERE datacenter_id = ? AND worker_id = ? ${dialect.lockSuffix}"
                ).use { statement ->
                    statement.setInt(1, datacenterId); statement.setInt(2, workerId)
                    statement.executeQuery().use { result ->
                        if (!result.next()) false
                        else {
                            currentFencingToken = result.getLong(3).let { if (result.wasNull()) 0L else it }
                            result.getString(1) == null ||
                                    (result.getLong(2) < runCatching {
                                        Math.subtractExact(now, clockSkewAllowanceMillis)
                                    }.getOrDefault(Long.MIN_VALUE) && !result.wasNull())
                        }
                    }
                }
                if (!available) { connection.rollback(); metrics.acquisitionFailed(); return null }
                val newFencingToken = Math.addExact(currentFencingToken, 1L)
                connection.prepareLeaseStatement(
                    "UPDATE $tableName SET owner_token = ?, lease_until = ?, fencing_token = ? " +
                            "WHERE datacenter_id = ? AND worker_id = ?"
                ).use { statement ->
                    statement.setString(1, token); statement.setLong(2, until); statement.setLong(3, newFencingToken)
                    statement.setInt(4, datacenterId); statement.setInt(5, workerId)
                    statement.executeUpdate()
                }
                connection.commit()
                return try {
                    JdbcWorkerIdLease(
                        workerId,
                        datacenterId,
                        token,
                        newFencingToken,
                        ttlMillis,
                        until,
                        heartbeatPeriod(ttlMillis),
                    ).also {
                        activeLeases += it
                        metrics.acquired()
                        metrics.activeLeases(activeLeases.size)
                    }
                } catch (failure: RuntimeException) {
                    runCatching { releaseAcquiredLease(workerId, datacenterId, token) }
                        .onFailure(failure::addSuppressed)
                    metrics.acquisitionFailed()
                    throw failure
                }
            } catch (ex: Exception) {
                metrics.acquisitionFailed()
                connection.rollback(); throw ex
            }
        }
    }

    private fun heartbeatPeriod(ttlMillis: Long): Long {
        val period = heartbeatIntervalMillis ?: (ttlMillis / 3).coerceAtLeast(1L)
        require(runCatching { Math.multiplyExact(period, heartbeatFailureThreshold.toLong()) }
            .getOrDefault(Long.MAX_VALUE) < ttlMillis) {
            "heartbeat interval and failure threshold must detect lease loss before TTL expiry"
        }
        return period
    }

    fun acquireAny(
        workerCount: Int,
        datacenterId: Int = 0,
        owner: String,
        ttlMillis: Long = 30_000L,
        acquisitionAttempts: Int = 1,
        acquisitionRetryDelayMillis: Long = 0L,
    ): WorkerIdLease {
        ensureOpen()
        require(acquisitionAttempts > 0) { "acquisitionAttempts must be > 0" }
        require(acquisitionRetryDelayMillis >= 0) { "acquisitionRetryDelayMillis must be >= 0" }
        var lastFailure: Throwable? = null
        repeat(acquisitionAttempts) { attempt ->
            try {
                initialize(workerCount, datacenterId)
                for (workerId in 0 until workerCount) {
                    tryAcquire(workerId, datacenterId, owner, ttlMillis)?.let { return it }
                }
            } catch (failure: RuntimeException) {
                lastFailure = failure
            }
            if (attempt + 1 < acquisitionAttempts) sleepBeforeAcquisitionRetry(acquisitionRetryDelayMillis)
        }
        throw IllegalStateException(
            "No worker identity is available or the lease backend did not recover: " +
                    "datacenterId=$datacenterId, workerCount=$workerCount",
            lastFailure,
        )
    }

    private fun sleepBeforeAcquisitionRetry(delayMillis: Long) {
        if (delayMillis == 0L) return
        try {
            Thread.sleep(delayMillis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while retrying JDBC worker lease acquisition", interrupted)
        }
    }

    private fun ensureOpen() {
        check(!closed.get()) { "JDBC worker lease store is already closed" }
    }

    private fun releaseAcquiredLease(workerId: Int, datacenterId: Int, token: String) {
        dataSource.connection.use { connection ->
            connection.prepareLeaseStatement(
                "UPDATE $tableName SET owner_token = NULL, lease_until = NULL WHERE datacenter_id = ? AND worker_id = ? AND owner_token = ?",
            ).use { statement ->
                statement.setInt(1, datacenterId)
                statement.setInt(2, workerId)
                statement.setString(3, token)
                statement.executeUpdate()
            }
        }
    }

    private inner class JdbcWorkerIdLease(
        override val workerId: Int,
        override val datacenterId: Int,
        private val token: String,
        override val fencingToken: Long,
        private val ttlMillis: Long,
        initialLeaseUntilMillis: Long,
        heartbeatPeriodMillis: Long,
    ) : WorkerIdLease {
        private val valid = AtomicBoolean(true)
        private val heartbeatFailures = java.util.concurrent.atomic.AtomicInteger()
        @Volatile private var leaseUntilMillis = initialLeaseUntilMillis
        private val heartbeat = scheduler.scheduleAtFixedRate(
            { renew() },
            heartbeatPeriodMillis,
            heartbeatPeriodMillis,
            TimeUnit.MILLISECONDS,
        )
        override val isValid: Boolean
            get() {
                if (valid.get() && remainingTtlMillis <= 0) {
                    invalidate(IllegalStateException("JDBC worker lease TTL elapsed locally"))
                }
                return valid.get()
            }
        override val remainingTtlMillis: Long
            get() = (leaseUntilMillis - effectiveClock.millis()).coerceAtLeast(0L)

        private fun renew() {
            if (!valid.get()) return
            try {
                val until = Math.addExact(effectiveClock.millis(), ttlMillis)
                dataSource.connection.use { connection ->
                    connection.prepareLeaseStatement(
                        "UPDATE $tableName SET lease_until = ? WHERE datacenter_id = ? AND worker_id = ? AND owner_token = ?"
                    ).use { statement ->
                        statement.setLong(1, until); statement.setInt(2, datacenterId); statement.setInt(3, workerId); statement.setString(4, token)
                        if (statement.executeUpdate() != 1) {
                            metrics.heartbeatFailed()
                            if (heartbeatFailures.incrementAndGet() >= heartbeatFailureThreshold) {
                                invalidate(IllegalStateException("JDBC worker lease was lost"))
                            }
                        } else {
                            leaseUntilMillis = until
                            heartbeatFailures.set(0)
                            metrics.heartbeatSucceeded()
                        }
                    }
                }
            } catch (failure: Exception) {
                metrics.heartbeatFailed()
                // A connection exception does not prove that ownership was lost. Keep the lease
                // usable until the last confirmed local deadline; isValid (and this heartbeat
                // loop) will fail closed once that deadline has elapsed.
                if (remainingTtlMillis <= 0) invalidate(failure)
            }
        }

        private fun invalidate(cause: Throwable) {
            if (valid.getAndSet(false)) {
                runCatching { failureListener.onFailure(workerId, datacenterId, cause) }
                activeLeases.remove(this)
                metrics.activeLeases(activeLeases.size)
            }
        }

        override fun close() {
            if (!valid.getAndSet(false)) {
                heartbeat.cancel(false)
                if (activeLeases.remove(this)) metrics.released()
                metrics.activeLeases(activeLeases.size)
                return
            }
            heartbeat.cancel(false)
            runCatching {
                    dataSource.connection.use { connection ->
                        connection.prepareLeaseStatement("UPDATE $tableName SET owner_token = NULL, lease_until = NULL WHERE datacenter_id = ? AND worker_id = ? AND owner_token = ?").use { statement ->
                        statement.setInt(1, datacenterId); statement.setInt(2, workerId); statement.setString(3, token); statement.executeUpdate()
                    }
                }
            }
            activeLeases.remove(this)
            metrics.released()
            metrics.activeLeases(activeLeases.size)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeLeases.toList().forEach { it.close() }
    }

    private fun java.sql.Connection.prepareLeaseStatement(sql: String): java.sql.PreparedStatement =
        prepareStatement(sql).also { if (statementTimeoutSeconds > 0) it.queryTimeout = statementTimeoutSeconds }

    private companion object {
        fun requireValidTableName(value: String) {
            require(value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "tableName must be a simple SQL identifier" }
        }
    }
}
