package io.github.dornol.idkit.flake

import io.github.dornol.idkit.IdGenerator
import io.github.dornol.idkit.IdGeneratorListener
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
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
 * ### Clock-regression model (since 3.0.0)
 *
 * Real-world wall clocks move backwards: NTP slews, VM pause/resume, container jitter. The
 * generator tolerates small regressions up to [clockRegressionTolerance] by **pinning** the
 * internal timestamp to the last emitted value and consuming the sequence space of that
 * pinned slice. When the sequence exhausts under a pinned clock, the internal timestamp is
 * advanced by one slice ("borrow from the future"), as long as the total lead over the wall
 * clock stays within tolerance. If the internal timestamp would lead the wall clock by more
 * than [clockRegressionTolerance], [ClockMovedBackwardsException] is raised. Passing
 * `Duration.ZERO` reproduces the pre-3.0 strict behavior (any backwards movement throws).
 *
 * ### Behavior
 *  - **Thread safety**: [nextId] is serialized via `@Synchronized`.
 *  - **Sequence overflow**: when the sequence field is exhausted within one timestamp slice,
 *    the generator either borrows the next slice (tolerant mode) or busy-waits with
 *    [Thread.onSpinWait] until the wall clock advances (strict mode,
 *    `clockRegressionTolerance == Duration.ZERO`). Both modes preserve strict id monotonicity.
 *  - **Clock regression**: see the model section above. Before 3.0.0 the generator threw on
 *    any backwards movement; the new default absorbs up to 10 ms.
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
    /**
     * Maximum amount of wall-clock regression the generator will absorb before throwing.
     *
     *  - [Duration.ZERO]: strict fail-fast — any backwards movement of the wall clock, or any
     *    sequence overflow that would require running ahead of the wall clock, throws
     *    [ClockMovedBackwardsException]. This matches the pre-3.0.0 behavior.
     *  - `Duration.ofMillis(N)` where `N > 0`: the generator's internal timestamp may lead the
     *    wall clock by at most `N` ms. Within that budget, regressions are absorbed by pinning
     *    to the last-emitted timestamp and sequence overflows borrow the next slice. Beyond
     *    `N` ms of lead, [ClockMovedBackwardsException] is raised.
     *
     * The default of 10 ms is sized to absorb typical NTP slews and container jitter while
     * still detecting genuinely large clock steps. Set to `Duration.ZERO` for applications
     * where any backwards movement must be surfaced immediately.
     *
     * @since 3.0.0
     */
    val clockRegressionTolerance: Duration = DEFAULT_CLOCK_REGRESSION_TOLERANCE,
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

    /** Cached tolerance in milliseconds for the hot path. */
    private val toleranceMillis: Long

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
        require(!clockRegressionTolerance.isNegative) {
            "clockRegressionTolerance must be >= 0, but was $clockRegressionTolerance"
        }

        val totalBits = UNUSED_BITS + timestampBits + datacenterIdBits + workerIdBits
        sequenceBits = 64 - totalBits
        maxSequence = (1L shl sequenceBits) - 1

        timestampLeftShift = datacenterIdBits + workerIdBits + sequenceBits
        datacenterIdLeftShift = workerIdBits + sequenceBits
        workerIdLeftShift = sequenceBits

        maxTimestamp = (1L shl timestampBits) - 1
        toleranceMillis = clockRegressionTolerance.toMillis()

        if (log.isDebugEnabled) {
            log.debug(
                "Initialized FlakeIdGenerator: timestampBits={}, workerIdBits={}, sequenceBits={}, maxSequence={}, clockRegressionTolerance={}ms",
                timestampBits, workerIdBits, sequenceBits, maxSequence, toleranceMillis
            )
        }
    }

    /**
     * Generates the next id (synchronized).
     *
     *  - Increments the sequence when the timestamp slice equals the previous one.
     *  - Absorbs backward clock movement up to [clockRegressionTolerance] by pinning to the
     *    last-emitted timestamp.
     *  - On sequence overflow, advances to the next slice (either by waiting for the wall
     *    clock in strict mode, or by borrowing one slice ahead in tolerant mode).
     *  - Throws [ClockMovedBackwardsException] when the observed regression exceeds
     *    [clockRegressionTolerance], or when a borrow under tolerant mode would push the
     *    internal timestamp more than that many milliseconds ahead of the wall clock.
     *
     * All validation runs **before** any internal state is mutated, so a thrown exception leaves
     * the generator instance uncorrupted and re-callable. The only exception is
     * [IllegalStateException] on timestamp overflow: because wall-clock time only moves forward,
     * that state is effectively non-recoverable on the same instance.
     *
     * @throws ClockMovedBackwardsException if the drift exceeds [clockRegressionTolerance].
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
            val driftSlices = lastGeneratedTimestamp - timestamp
            val driftMs = driftSlices * timestampDivisor
            if (driftMs > toleranceMillis) {
                reportClockRegression(driftSlices)
            }
            // Absorb: notify observers, then pin to last-emitted so the sequence-advance path
            // below treats this as a same-slice re-entry.
            listener.onClockRegression(driftMs)
            timestamp = lastGeneratedTimestamp
        }

        val nextSequence: Long = if (timestamp == lastGeneratedTimestamp) {
            val candidate = (sequenceCounter + 1) and maxSequence
            if (candidate == 0L) {
                listener.onSequenceOverflow()
                timestamp = advanceToNextSlice(timestamp)
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
     * Advances the pinned slice to the next one, returning the new slice value.
     *
     * Strict mode ([clockRegressionTolerance] is `Duration.ZERO`):
     *  - Busy-spins with [Thread.onSpinWait] until the wall clock's slice strictly exceeds
     *    `current`, throwing [ClockMovedBackwardsException] if the wall clock regresses during
     *    the spin (so a backwards jump never stalls the caller).
     *
     * Tolerant mode ([clockRegressionTolerance] is positive):
     *  - Borrows immediately: returns `current + 1` without waiting for the wall clock to tick.
     *    The caller's internal timestamp therefore leads the wall clock, bounded by the
     *    tolerance. If the resulting lead would exceed [clockRegressionTolerance],
     *    [ClockMovedBackwardsException] is thrown instead.
     */
    private fun advanceToNextSlice(current: Long): Long {
        // The loop only iterates in strict mode — tolerant mode always returns on the first
        // trip (either the wall-clock moved on, or we borrow and return).
        while (true) {
            val wallSlice = computeSlice(currentEpochMillis())
            if (wallSlice > current) return wallSlice

            if (toleranceMillis > 0L) {
                val borrowed = current + 1
                val driftSlices = borrowed - wallSlice
                if (driftSlices * timestampDivisor > toleranceMillis) {
                    reportClockRegression(driftSlices)
                }
                return borrowed
            }

            // Strict mode: a regression observed during the spin must fail fast instead of
            // waiting out the backward jump.
            if (wallSlice < current) {
                reportClockRegression(current - wallSlice)
            }
            Thread.onSpinWait()
        }
    }

    /**
     * Notifies the listener and throws [ClockMovedBackwardsException]. Takes the drift in
     * *slices* (not ms) — this is what the call sites hold — and scales it to ms before
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

    companion object {
        /**
         * Default tolerance of 10 ms. Chosen to absorb typical NTP slews and container
         * jitter while still detecting genuinely large clock steps. Callers that require
         * strict fail-fast behavior can pass [Duration.ZERO] instead.
         *
         * @since 3.0.0
         */
        @JvmField
        val DEFAULT_CLOCK_REGRESSION_TOLERANCE: Duration = Duration.ofMillis(10)
    }
}
