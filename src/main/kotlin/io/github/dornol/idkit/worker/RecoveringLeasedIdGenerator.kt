package io.github.dornol.idkit.worker

import io.github.dornol.idkit.IdGenerator
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Reacquires a worker lease after it expires and atomically swaps to a generator built for the
 * new worker identity. Calls fail closed while no valid lease is available.
 */
class RecoveringLeasedIdGenerator<T>(
    initialLease: WorkerIdLease,
    initialGenerator: IdGenerator<T>,
    private val scheduler: ScheduledExecutorService,
    recoveryRetryDelayMillis: Long = 1_000L,
    private val acquire: () -> WorkerIdLease,
    private val generatorFactory: (WorkerIdLease) -> IdGenerator<T>,
    private val metrics: LeaseRecoveryMetrics = NoopLeaseRecoveryMetrics,
) : IdGenerator<T>, LeaseRecoveryStatus, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val recovering = AtomicBoolean(false)
    private val state = AtomicReference(State(LeasedIdGenerator(initialGenerator, initialLease)))
    private val lastFailure = AtomicReference<Throwable?>(null)
    private val attempts = AtomicLong()
    private val failures = AtomicLong()
    private val recovery: ScheduledFuture<*>

    init {
        require(recoveryRetryDelayMillis > 0) { "recoveryRetryDelayMillis must be > 0" }
        recovery = scheduler.scheduleWithFixedDelay(
            { recoverIfNeeded() },
            recoveryRetryDelayMillis,
            recoveryRetryDelayMillis,
            TimeUnit.MILLISECONDS,
        )
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

    private fun recoverIfNeeded() {
        if (closed.get()) return
        val current = state.get()
        if (current.generator.lease.isValid || !recovering.compareAndSet(false, true)) return
        attempts.incrementAndGet()
        metrics.recoveryAttempted()
        metrics.recoveryActive(true)
        try {
            val newLease = acquire()
            try {
                val replacement = State(LeasedIdGenerator(generatorFactory(newLease), newLease))
                val previous = state.getAndSet(replacement)
                previous.generator.lease.close()
                lastFailure.set(null)
                metrics.recoverySucceeded()
            } catch (failure: Exception) {
                newLease.close()
                throw failure
            }
        } catch (failure: Exception) {
            failures.incrementAndGet()
            metrics.recoveryFailed()
            lastFailure.set(failure)
        } finally {
            recovering.set(false)
            metrics.recoveryActive(false)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        recovery.cancel(false)
        state.get().generator.lease.close()
    }

    private data class State<T>(val generator: LeasedIdGenerator<T>)
}
