package io.github.dornol.idkit.worker

import io.github.dornol.idkit.IdGenerator
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ThreadLocalRandom
import java.util.function.Function
import java.util.function.Supplier

/**
 * Reacquires a worker lease after it expires and atomically swaps to a generator built for the
 * new worker identity. Calls fail closed while no valid lease is available.
 */
class RecoveringLeasedIdGenerator<T>(
    initialLease: WorkerIdLease,
    initialGenerator: IdGenerator<T>,
    private val scheduler: ScheduledExecutorService,
    private val recoveryScheduler: ScheduledExecutorService = scheduler,
    private val recoveryRetryDelayMillis: Long = 1_000L,
    private val recoveryRetryJitterMillis: Long = 0L,
    private val recoveryMaxRetryDelayMillis: Long = recoveryRetryDelayMillis,
    private val acquire: () -> WorkerIdLease,
    private val generatorFactory: (WorkerIdLease) -> IdGenerator<T>,
    private val metrics: LeaseRecoveryMetrics = NoopLeaseRecoveryMetrics,
) : IdGenerator<T>, LeaseRecoveryStatus, AutoCloseable {
    companion object {
        /** Java-friendly factory for configuring automatic lease recovery. */
        @JvmStatic
        @JvmOverloads
        fun <T> create(
            initialLease: WorkerIdLease,
            initialGenerator: IdGenerator<T>,
            scheduler: ScheduledExecutorService,
            recoveryRetryDelayMillis: Long,
            acquire: Supplier<WorkerIdLease>,
            generatorFactory: Function<WorkerIdLease, IdGenerator<T>>,
            metrics: LeaseRecoveryMetrics = NoopLeaseRecoveryMetrics,
        ): RecoveringLeasedIdGenerator<T> = RecoveringLeasedIdGenerator(
            initialLease = initialLease,
            initialGenerator = initialGenerator,
            scheduler = scheduler,
            recoveryRetryDelayMillis = recoveryRetryDelayMillis,
            acquire = acquire::get,
            generatorFactory = generatorFactory::apply,
            metrics = metrics,
        )
    }

    private val closed = AtomicBoolean(false)
    private val recovering = AtomicBoolean(false)
    private val state = AtomicReference(State(LeasedIdGenerator(initialGenerator, initialLease)))
    private val lastFailure = AtomicReference<Throwable?>(null)
    private val attempts = AtomicLong()
    private val failures = AtomicLong()
    private var recovery: ScheduledFuture<*> = NoopScheduledFuture
    private val lifecycleLock = Any()
    private val consecutiveRecoveryFailures = AtomicLong()

    init {
        require(recoveryRetryDelayMillis > 0) { "recoveryRetryDelayMillis must be > 0" }
        require(recoveryRetryJitterMillis >= 0) { "recoveryRetryJitterMillis must be >= 0" }
        require(recoveryMaxRetryDelayMillis >= recoveryRetryDelayMillis) {
            "recoveryMaxRetryDelayMillis must be >= recoveryRetryDelayMillis"
        }
        recovery = scheduleRecovery(recoveryRetryDelayMillis)
    }

    override val currentLease: WorkerIdLease
        get() = state.get().generator.lease

    override val isRecovering: Boolean
        get() = recovering.get()

    override val lastRecoveryFailure: Throwable?
        get() = lastFailure.get()

    override val recoveryAttempts: Long
        get() = attempts.get()

    override val recoveryFailures: Long
        get() = failures.get()

    override fun nextId(): T {
        check(!closed.get()) { "Recovering ID generator is already closed" }
        val current = state.get()
        check(current.generator.lease.isValid) {
            "Worker identity lease is unavailable; ID generation is temporarily paused"
        }
        return current.generator.nextId()
    }

    override fun nextIds(count: Int): List<T> {
        require(count >= 0) { "count must be >= 0, but was $count" }
        if (count == 0) return emptyList()
        return List(count) { nextId() }
    }

    private fun recoverIfNeeded(): Boolean {
        if (closed.get()) return true
        val current = state.get()
        if (current.generator.lease.isValid || !recovering.compareAndSet(false, true)) return true
        attempts.incrementAndGet()
        metrics.recoveryAttempted()
        metrics.recoveryActive(true)
        try {
            val newLease = acquire()
            try {
                val replacement = State(LeasedIdGenerator(generatorFactory(newLease), newLease))
                synchronized(lifecycleLock) {
                    if (closed.get()) {
                        replacement.generator.lease.close()
                        return true
                    }
                    val previous = state.getAndSet(replacement)
                    previous.generator.lease.close()
                    lastFailure.set(null)
                    metrics.recoverySucceeded()
                    consecutiveRecoveryFailures.set(0)
                }
            } catch (failure: Exception) {
                newLease.close()
                throw failure
            }
        } catch (failure: Exception) {
            failures.incrementAndGet()
            metrics.recoveryFailed()
            lastFailure.set(failure)
            consecutiveRecoveryFailures.incrementAndGet()
        } finally {
            recovering.set(false)
            metrics.recoveryActive(false)
        }
        return consecutiveRecoveryFailures.get() == 0L
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            recovery.cancel(false)
            state.get().generator.lease.close()
        }
    }

    private fun scheduleRecovery(baseDelayMillis: Long): ScheduledFuture<*> = synchronized(lifecycleLock) {
        if (closed.get()) return@synchronized NoopScheduledFuture
        recoveryScheduler.schedule(
            {
                val recovered = recoverIfNeeded()
                if (!closed.get()) {
                    val nextDelay = if (recovered) {
                        recoveryRetryDelayMillis
                    } else {
                        backoffDelayMillis()
                    }
                    synchronized(lifecycleLock) {
                        if (!closed.get()) {
                            recovery = scheduleRecovery(nextDelay)
                        }
                    }
                }
            },
            jitteredDelay(baseDelayMillis),
            TimeUnit.MILLISECONDS,
        )
    }

    private fun backoffDelayMillis(): Long {
        val failures = consecutiveRecoveryFailures.get().coerceAtMost(62L)
        val multiplier = 1L shl (failures - 1).toInt().coerceAtLeast(0)
        return runCatching { Math.multiplyExact(recoveryRetryDelayMillis, multiplier) }
            .getOrDefault(recoveryMaxRetryDelayMillis)
            .coerceAtMost(recoveryMaxRetryDelayMillis)
    }

    private fun jitteredDelay(delayMillis: Long): Long {
        if (recoveryRetryJitterMillis == 0L) return delayMillis
        val jitter = ThreadLocalRandom.current().nextLong(recoveryRetryJitterMillis + 1)
        return (delayMillis + jitter).coerceAtMost(Long.MAX_VALUE)
    }

    private object NoopScheduledFuture : ScheduledFuture<Any?> {
        override fun getDelay(unit: TimeUnit): Long = 0
        override fun compareTo(other: java.util.concurrent.Delayed): Int = 0
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        override fun isCancelled(): Boolean = false
        override fun isDone(): Boolean = true
        override fun get(): Any? = null
        override fun get(timeout: Long, unit: TimeUnit): Any? = null
    }

    private data class State<T>(val generator: LeasedIdGenerator<T>)
}
