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
 * itself remains local and never performs a Redis round trip. A failed heartbeat invalidates the
 * lease; wrap the generator in [io.github.dornol.idkit.worker.LeasedIdGenerator] to fail closed.
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
) : WorkerIdLeaseStore, AutoCloseable {

    init { require(heartbeatFailureThreshold > 0) { "heartbeatFailureThreshold must be > 0" } }

    private val activeLeases = ConcurrentHashMap.newKeySet<RedisWorkerIdLease>()

    override fun tryAcquire(
        workerId: Int,
        datacenterId: Int,
        owner: String,
        ttlMillis: Long,
    ): WorkerIdLease? {
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

        return RedisWorkerIdLease(workerId, datacenterId, key, token, acquired, ttlMillis).also {
            activeLeases += it
            metrics.acquired()
            metrics.activeLeases(activeLeases.size)
        }
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
    ): WorkerIdLease {
        require(workerCount > 0) { "workerCount must be > 0" }
        for (workerId in 0 until workerCount) {
            tryAcquire(workerId, datacenterId, owner, ttlMillis)?.let { return it }
        }
        throw IllegalStateException(
            "No worker identity is available: datacenterId=$datacenterId, workerCount=$workerCount"
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
    ) : WorkerIdLease {
        private val storedToken = "$fencingToken|$token"
        private val valid = AtomicBoolean(true)
        private val heartbeatFailures = java.util.concurrent.atomic.AtomicInteger()
        @Volatile private var leaseUntilMillis = System.currentTimeMillis() + ttlMillis
        private val heartbeat = scheduler.scheduleAtFixedRate(
            { renew() },
            ttlMillis / 3,
            (ttlMillis / 3).coerceAtLeast(1L),
            TimeUnit.MILLISECONDS,
        )

        override val isValid: Boolean
            get() = valid.get()
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
                if (heartbeatFailures.incrementAndGet() >= heartbeatFailureThreshold) invalidate(failure)
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
