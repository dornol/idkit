package io.github.dornol.idkit.redis

import io.github.dornol.idkit.worker.WorkerIdLease
import io.github.dornol.idkit.worker.WorkerIdLeaseStore
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.sync.RedisCommands
import java.security.SecureRandom
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Redis-backed worker identity leases.
 *
 * The Redis connection is used only during acquisition, heartbeat, and close. ID generation
 * itself remains local and never performs a Redis round trip. A definitive ownership loss or an
 * expired local deadline invalidates the lease; wrap the generator in
 * [io.github.dornol.idkit.worker.LeasedIdGenerator] to fail closed.
 *
 * The supplied [RedisCommands] connection must be safe to use from the heartbeat thread. Lettuce
 * synchronous connections satisfy this requirement when commands are not blocking.
 */
class RedisWorkerIdLeaseStore(
    private val commands: RedisCommands<String, String>,
    private val scheduler: ScheduledExecutorService,
    private val keyPrefix: String = "idkit:worker",
    private val random: SecureRandom = SecureRandom(),
    private val failureListener: RedisLeaseFailureListener = RedisLeaseFailureListener { _, _, _ -> },
    private val metrics: RedisLeaseMetrics = NoopRedisLeaseMetrics,
    private val heartbeatFailureThreshold: Int = 1,
    private val heartbeatIntervalMillis: Long? = null,
) : WorkerIdLeaseStore, AutoCloseable {

    init {
        require(heartbeatFailureThreshold in 1..2) {
            "heartbeatFailureThreshold must be between 1 and 2 so the lease fails before TTL expiry"
        }
        require(heartbeatIntervalMillis == null || heartbeatIntervalMillis > 0) {
            "heartbeatIntervalMillis must be > 0 when specified"
        }
    }

    private val activeLeases = ConcurrentHashMap.newKeySet<RedisWorkerIdLease>()
    private val closed = AtomicBoolean(false)

    override fun tryAcquire(
        workerId: Int,
        datacenterId: Int,
        owner: String,
        ttlMillis: Long,
    ): WorkerIdLease? {
        ensureOpen()
        require(workerId >= 0) { "workerId must be >= 0" }
        require(datacenterId >= 0) { "datacenterId must be >= 0" }
        require(owner.isNotBlank()) { "owner must not be blank" }
        require(ttlMillis > 0) { "ttlMillis must be > 0" }

        val token = "$owner:${java.lang.Long.toUnsignedString(random.nextLong(), 16)}"
        val key = key(datacenterId, workerId)
        val fenceKey = fencingKey(datacenterId, workerId)
        val acquired = commands.eval<Long>(
            ACQUIRE_SCRIPT,
            ScriptOutputType.INTEGER,
            arrayOf(key, fenceKey),
            token,
            ttlMillis.toString(),
        )
        if (acquired <= 0L) { metrics.acquisitionFailed(); return null }

        return try {
            RedisWorkerIdLease(
                workerId,
                datacenterId,
                key,
                token,
                acquired,
                ttlMillis,
                heartbeatPeriod(ttlMillis),
            ).also {
                activeLeases += it
                metrics.acquired()
                metrics.activeLeases(activeLeases.size)
            }
        } catch (failure: RuntimeException) {
            runCatching { releaseAcquiredLease(key, "$acquired|$token") }
                .onFailure(failure::addSuppressed)
            metrics.acquisitionFailed()
            throw failure
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

    /** Reads the Redis value and remaining key TTL without changing the lease. */
    fun inspect(workerId: Int, datacenterId: Int): RedisLeaseStatus {
        require(workerId >= 0) { "workerId must be >= 0" }
        require(datacenterId >= 0) { "datacenterId must be >= 0" }
        val key = key(datacenterId, workerId)
        val ttl = commands.pttl(key)
        val rawToken = commands.get(key)
        val fenceSeparator = rawToken?.indexOf('|') ?: -1
        val fencingToken = if (fenceSeparator > 0) rawToken!!.substring(0, fenceSeparator).toLongOrNull() ?: 0L else 0L
        val ownerToken = if (fenceSeparator > 0) rawToken!!.substring(fenceSeparator + 1) else rawToken
        return RedisLeaseStatus(
            workerId = workerId,
            datacenterId = datacenterId,
            owner = ownerToken?.substringBefore(':')?.takeIf { it.isNotEmpty() },
            tokenFingerprint = ownerToken?.let { Integer.toHexString(it.hashCode()) },
            fencingToken = fencingToken,
            remainingTtlMillis = ttl,
        )
    }

    /** Acquires the first available worker ID in `0 until workerCount`. */
    fun acquireAny(
        workerCount: Int,
        datacenterId: Int = 0,
        owner: String,
        ttlMillis: Long = 30_000L,
        acquisitionAttempts: Int = 1,
        acquisitionRetryDelayMillis: Long = 0L,
    ): WorkerIdLease {
        ensureOpen()
        require(workerCount > 0) { "workerCount must be > 0" }
        require(acquisitionAttempts > 0) { "acquisitionAttempts must be > 0" }
        require(acquisitionRetryDelayMillis >= 0) { "acquisitionRetryDelayMillis must be >= 0" }
        var lastFailure: Throwable? = null
        repeat(acquisitionAttempts) { attempt ->
            try {
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
            throw IllegalStateException("Interrupted while retrying Redis worker lease acquisition", interrupted)
        }
    }

    private fun ensureOpen() {
        check(!closed.get()) { "Redis worker lease store is already closed" }
    }

    private fun releaseAcquiredLease(key: String, storedToken: String) {
        commands.eval<Long>(
            RELEASE_SCRIPT,
            ScriptOutputType.INTEGER,
            arrayOf(key),
            storedToken,
        )
    }

    private fun key(datacenterId: Int, workerId: Int): String =
        "$keyPrefix:$datacenterId:$workerId"

    private fun fencingKey(datacenterId: Int, workerId: Int): String =
        "$keyPrefix:fence:$datacenterId:$workerId"

    private inner class RedisWorkerIdLease(
        override val workerId: Int,
        override val datacenterId: Int,
        private val key: String,
        private val token: String,
        override val fencingToken: Long,
        private val ttlMillis: Long,
        heartbeatPeriodMillis: Long,
    ) : WorkerIdLease {
        private val storedToken = "$fencingToken|$token"
        private val valid = AtomicBoolean(true)
        private val heartbeatFailures = java.util.concurrent.atomic.AtomicInteger()
        @Volatile private var leaseUntilMillis = System.currentTimeMillis() + ttlMillis
        private val heartbeat = scheduler.scheduleAtFixedRate(
            { renew() },
            heartbeatPeriodMillis,
            heartbeatPeriodMillis,
            TimeUnit.MILLISECONDS,
        )

        override val isValid: Boolean
            get() {
                if (valid.get() && remainingTtlMillis <= 0) {
                    invalidate(IllegalStateException("Redis worker lease TTL elapsed locally"))
                }
                return valid.get()
            }
        override val remainingTtlMillis: Long
            get() = (leaseUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)

        private fun renew() {
            if (!valid.get()) return
            try {
                val renewed = commands.eval<Long>(
                    RENEW_SCRIPT,
                    ScriptOutputType.INTEGER,
                    arrayOf(key),
                    storedToken,
                    ttlMillis.toString(),
                )
                if (renewed != 1L) {
                    metrics.heartbeatFailed()
                    if (heartbeatFailures.incrementAndGet() >= heartbeatFailureThreshold) {
                        invalidate(IllegalStateException("Redis worker lease was lost"))
                    }
                } else {
                    leaseUntilMillis = System.currentTimeMillis() + ttlMillis
                    heartbeatFailures.set(0)
                    metrics.heartbeatSucceeded()
                }
            } catch (failure: RuntimeException) {
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
                commands.eval<Long>(
                    RELEASE_SCRIPT,
                    ScriptOutputType.INTEGER,
                    arrayOf(key),
                    storedToken,
                )
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

    private companion object {
        private const val ACQUIRE_SCRIPT =
            "if redis.call('exists', KEYS[1]) == 0 then " +
                    "local fence = redis.call('incr', KEYS[2]); " +
                    "redis.call('psetex', KEYS[1], ARGV[2], fence .. '|' .. ARGV[1]); return fence " +
                    "else return 0 end"
        private const val RENEW_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "redis.call('pexpire', KEYS[1], ARGV[2]); return 1 " +
                    "else return 0 end"
        private const val RELEASE_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "redis.call('del', KEYS[1]); return 1 " +
                    "else return 0 end"
    }
}
