package io.github.dornol.idkit.uuidv7

import io.github.dornol.idkit.IdGenerator
import io.github.dornol.idkit.IdGeneratorListener
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * UUID v7 generator compliant with RFC 9562 §6.2 **Method 2 — Fixed-Length Dedicated Counter Bits**.
 *
 *  - UUID v7 places 48 bits of Unix epoch milliseconds in the high part and combines
 *    version/variant markers with random bits, yielding a time-sortable format.
 *  - This implementation guarantees **intra-millisecond monotonicity** by repurposing the 12-bit
 *    `rand_a` field as a dedicated counter.
 *  - The timestamp (52 bits) and counter (12 bits) are packed into a single [AtomicLong] and
 *    updated atomically via CAS, so every emitted UUID is strictly increasing when compared by
 *    `mostSignificantBits`, even under heavy concurrency.
 *  - When the 12-bit counter (4096) is exhausted within the same millisecond, the timestamp is
 *    advanced by 1 ms and the counter reset to 0 (RFC 9562's "borrow from the clock"). Once the
 *    real wall clock catches up, the stored timestamp naturally realigns.
 *  - **No drift cap**: under sustained load that exceeds ~4,096 ids/ms (~4 million ids/sec)
 *    on a single generator, borrowed timestamps accumulate and the embedded timestamp will
 *    drift ahead of the wall clock. This is fine for most workloads, but external observers
 *    may see UUIDs whose timestamp component lies slightly in the future under extreme load.
 *
 * ### Bit layout
 *  - `mostSigBits` (high 64 bits)
 *    - 0..47  : Unix epoch milliseconds (48 bits)
 *    - 48..51 : version = 0b0111 (7)
 *    - 52..63 : monotonic counter (12 bits, the `rand_a` slot)
 *  - `leastSigBits` (low 64 bits)
 *    - 62..63 : variant = 0b10 (IETF RFC 4122)
 *    - 0..61  : random 62 bits (`rand_b`)
 *
 * ### Changes vs earlier versions
 *  - 2.0.0: `rand_a`'s 12 bits switched from fully random to a **monotonic counter**.
 *    Within the same millisecond, comparing two UUIDs by `mostSignificantBits` is now
 *    strictly increasing. Collision probability is unchanged: `rand_b`'s 62 random bits are
 *    preserved.
 *  - 2.2.0: class is now `open` with a `protected open fun currentEpochMillis()` test seam
 *    aligning with [io.github.dornol.idkit.flake.FlakeIdGenerator] and
 *    [io.github.dornol.idkit.ulid.UlidIdGenerator]; `nextId()` is `final override` so a
 *    subclass cannot accidentally bypass the CAS-backed synchronization.
 */
open class UuidV7IdGenerator(
    private val clock: Clock = Clock.systemUTC(),
    private val listener: IdGeneratorListener = IdGeneratorListener.NOOP,
) : IdGenerator<UUID> {

    /**
     * Packed state `(timestamp:52bit | counter:12bit)`.
     *
     * Updated via CAS on a single [AtomicLong] so the timestamp and counter advance atomically.
     */
    private val state = AtomicLong(0L)

    /**
     * Generates a new UUID v7.
     *
     * 1. Compare the observed `now` to the stored `(ts, counter)` and compute the new packed
     *    state, then commit via CAS:
     *    - `now > ts`: new millisecond slice — `(now, 0)`.
     *    - `now <= ts` and `counter < MAX`: same ms or clock regression — `(ts, counter + 1)`.
     *    - `now <= ts` and `counter == MAX`: counter overflow — `(ts + 1, 0)`, borrowing from
     *      the clock.
     * 2. Place the timestamp in bits 0..47, version 7 in 48..51, and the counter in 52..63.
     * 3. Fill `leastSigBits` with the 2-bit variant `0b10` plus 62 random bits.
     */
    final override fun nextId(): UUID {
        val packed = nextState()
        val timestamp = packed ushr COUNTER_BITS
        val counter = packed and COUNTER_MASK

        // If the timestamp ever exceeds 48 bits (~year 10889), truncate the high bits to keep
        // the layout intact.
        val timePart = (timestamp and TIMESTAMP_MASK) shl 16
        val versionPart = 0x7L shl 12
        val mostSigBits = timePart or versionPart or counter

        // The variant overwrites the top 2 bits, so rand_b only needs to fill the low 62 bits.
        // `nextLong() and RAND_B_MASK` is cheaper than `nextLong(0, 1L shl 62)` (no bounded
        // rejection loop).
        val variant = 0x2L shl 62
        val lowRand = ThreadLocalRandom.current().nextLong() and RAND_B_MASK
        val leastSigBits = variant or lowRand

        return UUID(mostSigBits, leastSigBits)
    }

    /**
     * Returns the current wall-clock epoch milliseconds.
     *
     * Reads from the configured [Clock] (defaulting to `Clock.systemUTC()`). Remains
     * `protected open` for backward compatibility with earlier test patterns that subclassed
     * the generator and overrode this method; new code should inject a [Clock] via the
     * constructor instead.
     *
     * @since 2.2.0
     */
    protected open fun currentEpochMillis(): Long = clock.millis()

    /**
     * Atomically advances the packed `(timestamp | counter)` state and returns the new value.
     *
     * On CAS contention the losing thread retries; winners advance the counter in CAS order.
     */
    private fun nextState(): Long {
        while (true) {
            val prev = state.get()
            val prevTs = prev ushr COUNTER_BITS
            val prevCounter = prev and COUNTER_MASK
            val now = currentEpochMillis()

            val newTs: Long
            val newCounter: Long
            val counterBorrowed: Boolean
            if (now > prevTs) {
                newTs = now
                newCounter = 0L
                counterBorrowed = false
            } else if (prevCounter == COUNTER_MASK) {
                // Same ms, counter exhausted — borrow 1 ms from the clock.
                newTs = prevTs + 1
                newCounter = 0L
                counterBorrowed = true
            } else {
                // Same ms or clock regression — keep prev ts and bump the counter.
                newTs = prevTs
                newCounter = prevCounter + 1
                counterBorrowed = false
            }

            val newState = (newTs shl COUNTER_BITS) or newCounter
            if (state.compareAndSet(prev, newState)) {
                // Fire listeners only after CAS wins so contended retries don't double-report.
                // `now < prevTs` is strict: same-ms re-entry is not a regression.
                if (now < prevTs) listener.onClockRegression(prevTs - now)
                if (counterBorrowed) listener.onCounterBorrow()
                return newState
            }
        }
    }

    private companion object {
        private const val COUNTER_BITS = 12
        private const val COUNTER_MASK = 0xFFFL                 // (1L shl 12) - 1
        private const val TIMESTAMP_MASK = 0x0000FFFFFFFFFFFFL  // low 48 bits
        private const val RAND_B_MASK = 0x3FFFFFFFFFFFFFFFL     // low 62 bits (variant excluded)
    }
}
