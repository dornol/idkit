package io.github.dornol.idkit.flake

import io.github.dornol.idkit.IdGenerator
import io.github.dornol.idkit.IdGeneratorListener
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * Custom Flake/Snowflake ID Generator
 *
 * ID layout (64 bits total):
 *  - 1 bit  : sign bit (unused)
 *  - X bits : timestamp delta (elapsed since [epochStart], in `timestampDivisor` units)
 *  - Y bits : datacenterId
 *  - Z bits : workerId
 *  - R bits : sequence (counter within the same timestamp slice)
 *
 * Use [timestampDivisor] to coarsen the timestamp resolution beyond a single millisecond.
 *
 * ### Behavior
 *  - **Thread safety**: [nextId] is serialized via `@Synchronized`.
 *  - **Sequence overflow**: when the sequence field is exhausted within one timestamp slice,
 *    the generator busy-waits with [Thread.onSpinWait] until the next slice (bounded to at most
 *    one slice).
 *  - **Clock regression**: when [System.currentTimeMillis] returns a value less than the last
 *    observed timestamp, [ClockMovedBackwardsException] is thrown. The legacy "pin to last"
 *    strategy, combined with a sequence overflow, could busy-spin for minutes or hours waiting
 *    for the wall clock to catch up — failing fast is safer.
 *  - **Timestamp exhaustion**: exceeding the range representable in [timestampBits] raises
 *    [IllegalStateException]. Because wall-clock time only moves forward, this state is
 *    **non-recoverable**; the caller must reconstruct the generator with a wider [timestampBits]
 *    or a more recent [epochStart].
 *  - **Precise delta math**: the timestamp field is computed as `(now - epoch) / divisor`.
 *    The previous implementation computed `now/divisor - epoch/divisor`, which was off by ±1
 *    when `divisor > 1` and `epoch` was not a multiple of the divisor. This version uses the
 *    precise form.
 *
 * Inheritance note: the class is `open`, but [nextId] is a `final override` with `@Synchronized`
 * and cannot be re-overridden. Subclasses are intended to be thin wrappers like
 * [SnowflakeIdGenerator] that pre-bind a default bit layout. Tests that need a fake clock
 * should override [currentEpochMillis] instead.
 */
open class FlakeIdGenerator(
    val timestampBits: Int = 41,
    val datacenterIdBits: Int = 5,
    val workerIdBits: Int = 5,
    val timestampDivisor: Long = 1L,
    val epochStart: Instant = Instant.EPOCH,
    val datacenterId: Int,
    val workerId: Int,
    private val clock: Clock = Clock.systemUTC(),
    private val listener: IdGeneratorListener = IdGeneratorListener.NOOP,
) : IdGenerator<Long> {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Maximum representable worker / datacenter id. */
    val maxWorkerId: Int = (1 shl workerIdBits) - 1
    val maxDatacenterId: Int = (1 shl datacenterIdBits) - 1

    /** Raw epoch millis for precise delta math. */
    private val epochStartMillis: Long = epochStart.toEpochMilli()

    /** Sequence-related values (computed in init). */
    val sequenceBits: Int
    val maxSequence: Long

    /** Left-shift amounts for bit packing. */
    private val timestampLeftShift: Int
    private val datacenterIdLeftShift: Int
    private val workerIdLeftShift: Int

    /** Maximum value representable in the timestamp field. */
    private val maxTimestamp: Long

    /** Sequence counter within the current timestamp slice. */
    private var sequenceCounter = 0L

    /** Last emitted timestamp slice (delta from epoch in divisor units). */
    private var lastGeneratedTimestamp = -1L

    init {
        validateFlakeLayout(timestampBits, datacenterIdBits, workerIdBits, timestampDivisor)

        require(workerId in 0..maxWorkerId) {
            "workerId must be between 0 and $maxWorkerId, but was $workerId"
        }
        require(datacenterId in 0..maxDatacenterId) {
            "datacenterId must be between 0 and $maxDatacenterId, but was $datacenterId"
        }

        val totalBits = UNUSED_BITS + timestampBits + datacenterIdBits + workerIdBits
        sequenceBits = 64 - totalBits
        maxSequence = (1L shl sequenceBits) - 1

        timestampLeftShift = datacenterIdBits + workerIdBits + sequenceBits
        datacenterIdLeftShift = workerIdBits + sequenceBits
        workerIdLeftShift = sequenceBits

        maxTimestamp = (1L shl timestampBits) - 1

        if (log.isDebugEnabled) {
            log.debug(
                "Initialized FlakeIdGenerator: timestampBits={}, workerIdBits={}, sequenceBits={}, maxSequence={}",
                timestampBits, workerIdBits, sequenceBits, maxSequence
            )
        }
    }

    /**
     * Generates the next id (synchronized).
     *
     *  - Increments the sequence when the timestamp slice equals the previous one.
     *  - Waits for the next time slice when the sequence would overflow.
     *  - Throws [ClockMovedBackwardsException] if clock regression is observed.
     *
     * All validation runs **before** any internal state is mutated, so a thrown exception leaves
     * the generator instance uncorrupted and re-callable. The only exception is
     * [IllegalStateException] on timestamp overflow: because wall-clock time only moves forward,
     * that state is effectively non-recoverable on the same instance.
     *
     * @throws ClockMovedBackwardsException if the system clock returned a value smaller than the
     *   previously observed timestamp.
     * @throws IllegalStateException if the timestamp delta would exceed the maximum value
     *   representable in [timestampBits].
     */
    @Synchronized
    final override fun nextId(): Long = nextIdLocked()

    /**
     * Holds the monitor once for the whole batch, trading a longer critical section for lower
     * per-id lock overhead. Note: this *increases* wait time for concurrent callers — prefer
     * [nextId] in highly contended paths and reserve this for pre-allocation workloads
     * (e.g., ids for a bulk SQL insert).
     *
     * A mid-batch [ClockMovedBackwardsException] propagates and already-generated ids in this
     * call are discarded; the generator stays consistent for future calls once the clock recovers.
     *
     * @since 2.3.0
     */
    @Synchronized
    final override fun nextIds(count: Int): List<Long> {
        require(count >= 0) { "count must be >= 0, but was $count" }
        if (count == 0) return emptyList()
        return List(count) { nextIdLocked() }
    }

    /** Caller must hold this instance's monitor (guaranteed by `@Synchronized` on wrappers). */
    private fun nextIdLocked(): Long {
        var timestamp = computeSlice(currentEpochMillis())

        if (timestamp < lastGeneratedTimestamp) {
            reportClockRegression(lastGeneratedTimestamp - timestamp)
        }

        val nextSequence: Long = if (timestamp == lastGeneratedTimestamp) {
            val candidate = (sequenceCounter + 1) and maxSequence
            if (candidate == 0L) {
                // Sequence overflow — wait for the next slice and start from its first id.
                listener.onSequenceOverflow()
                timestamp = waitForNextSlice(timestamp)
                0L
            } else {
                candidate
            }
        } else {
            0L
        }

        check(timestamp in 0..maxTimestamp) {
            "Timestamp overflow: delta $timestamp exceeds $timestampBits-bit maximum ($maxTimestamp)"
        }

        // All validation passed — commit internal state.
        lastGeneratedTimestamp = timestamp
        sequenceCounter = nextSequence

        return (timestamp shl timestampLeftShift) or
                (datacenterId.toLong() shl datacenterIdLeftShift) or
                (workerId.toLong() shl workerIdLeftShift) or
                nextSequence
    }

    /**
     * Returns the current wall-clock epoch milliseconds.
     *
     * Reads from the configured [Clock] (defaulting to `Clock.systemUTC()`). Remains
     * `protected open` for backward compatibility with earlier test patterns that subclassed
     * the generator and overrode this method; new code should inject a [Clock] via the
     * constructor instead.
     *
     * @since 2.0.0
     */
    protected open fun currentEpochMillis(): Long = clock.millis()

    /** Converts wall-clock millis into an epoch-relative slice in divisor units (precise). */
    private fun computeSlice(nowMillis: Long): Long =
        (nowMillis - epochStartMillis) / timestampDivisor

    /**
     * Busy-spins until the slice strictly exceeds `currentSlice`, then returns the new slice.
     *
     * If the wall clock is observed to regress below `currentSlice` during the spin, throws
     * [ClockMovedBackwardsException] instead of waiting for the clock to catch back up — which
     * could otherwise block the caller for the duration of the backward jump (potentially
     * minutes or hours under a bad NTP correction).
     */
    private fun waitForNextSlice(currentSlice: Long): Long {
        while (true) {
            val slice = computeSlice(currentEpochMillis())
            if (slice > currentSlice) return slice
            if (slice < currentSlice) reportClockRegression(currentSlice - slice)
            Thread.onSpinWait()
        }
    }

    /**
     * Notifies the listener and throws [ClockMovedBackwardsException]. Takes the drift in
     * *slices* (not ms) — this is what the two call sites hold — and scales it to ms before
     * calling the listener, so `IdGeneratorListener.onClockRegression` receives real
     * wall-clock milliseconds regardless of [timestampDivisor].
     */
    private fun reportClockRegression(driftSlices: Long): Nothing {
        listener.onClockRegression(driftSlices * timestampDivisor)
        throw ClockMovedBackwardsException(
            driftAmount = driftSlices,
            timestampDivisor = timestampDivisor,
        )
    }
}
