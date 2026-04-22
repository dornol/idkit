package io.github.dornol.idkit.ulid

import io.github.dornol.idkit.IdGenerator
import io.github.dornol.idkit.IdGeneratorListener
import java.time.Clock
import java.util.concurrent.ThreadLocalRandom

/**
 * ULID (Universally Unique Lexicographically Sortable Identifier) generator.
 *
 * Emits 26-character strings encoded in Crockford's Base32:
 *  - First 10 chars: 48-bit timestamp (Unix epoch milliseconds)
 *  - Last 16 chars: 80-bit randomness
 *
 * ### Guarantees
 *  - **Monotonic within a millisecond**: when two ULIDs are generated in the same ms, the 80-bit
 *    randomness of the later one is the earlier one plus 1 (per the ULID spec's monotonic profile).
 *    The lexicographic order of the emitted strings therefore matches the order of generation.
 *  - **Clock regression**: if the wall clock is observed to move backwards, the previously held
 *    timestamp is reused and the randomness is incremented, preserving monotonicity.
 *  - **Overflow**: exhausting the 80-bit randomness within a single millisecond (~1.2 × 10²⁴ ids)
 *    throws [IllegalStateException]. In practice this is unreachable.
 *
 * Thread safety is provided by `@Synchronized`.
 *
 * The [currentEpochMillis] seam is `protected open` so tests can inject a fake clock. Do not
 * override it in production code.
 *
 * @since 2.1.0
 * @see <a href="https://github.com/ulid/spec">ULID spec</a>
 */
open class UlidIdGenerator(
    private val clock: Clock = Clock.systemUTC(),
    private val listener: IdGeneratorListener = IdGeneratorListener.NOOP,
) : IdGenerator<String> {

    /** Last observed timestamp in ms. `-1` means uninitialized. */
    private var lastTimestamp: Long = -1L

    /** High 16 bits of the 80-bit randomness (stored in the low bits of a Long). */
    private var randomHi: Long = 0L

    /** Low 64 bits of the 80-bit randomness. */
    private var randomLo: Long = 0L

    @Synchronized
    final override fun nextId(): String = nextIdLocked()

    /**
     * Holds the monitor once for the whole batch, trading a longer critical section for lower
     * per-id lock overhead. Prefer [nextId] in highly contended paths and reserve this for
     * pre-allocation workloads. Intra-ms monotonicity still holds: successive ULIDs in the
     * batch differ by +1 in the 80-bit randomness field.
     *
     * @since 2.3.0
     */
    @Synchronized
    final override fun nextIds(count: Int): List<String> {
        require(count >= 0) { "count must be >= 0, but was $count" }
        if (count == 0) return emptyList()
        return List(count) { nextIdLocked() }
    }

    /** Caller must hold this instance's monitor (guaranteed by `@Synchronized` on wrappers). */
    private fun nextIdLocked(): String {
        val now = currentEpochMillis()

        if (now > lastTimestamp) {
            // New ms slice — roll a fresh 80 bits of randomness.
            lastTimestamp = now
            val seed = drawRandomness()
            // Mask enforces the 16-bit contract even if an override returns wider values.
            randomHi = seed[0] and RANDOM_HI_MASK
            randomLo = seed[1]
        } else {
            // `now < lastTimestamp` is strict clock regression; `now == lastTimestamp` is
            // routine same-ms reuse and not reported.
            if (now < lastTimestamp) listener.onClockRegression(lastTimestamp - now)
            // Same ms or clock regression — keep the timestamp and bump the 80-bit value by 1.
            val newLo = randomLo + 1
            if (newLo == 0L) {
                // randomLo wrapped (was 0xFFFF_FFFF_FFFF_FFFF), carry into randomHi.
                val newHi = randomHi + 1
                if ((newHi and RANDOM_HI_MASK) == 0L) {
                    // Overflowed the full 80-bit randomness within this ms.
                    throw IllegalStateException(
                        "ULID randomness overflow within the same millisecond (ts=$lastTimestamp)"
                    )
                }
                randomHi = newHi
            }
            randomLo = newLo
        }

        return encodeUlid(lastTimestamp, randomHi, randomLo)
    }

    /**
     * Returns the current wall-clock epoch milliseconds.
     *
     * Reads from the configured [Clock] (defaulting to `Clock.systemUTC()`). Remains
     * `protected open` for backward compatibility with earlier test patterns that subclassed
     * the generator and overrode this method; new code should inject a [Clock] via the
     * constructor instead.
     */
    protected open fun currentEpochMillis(): Long = clock.millis()

    /**
     * Returns a fresh 80-bit randomness as a two-element `LongArray`: `[hi, lo]`.
     *  - `hi` is the high 16 bits (only the low 16 bits of the returned `Long` are used;
     *    higher bits are masked off by the caller).
     *  - `lo` is the low 64 bits.
     *
     * Exposed as `protected open` so tests can seed deterministic randomness (for example, to
     * force the 80-bit overflow path). Called only on new-ms transitions, so allocation cost
     * is negligible. Do not override in production code.
     */
    protected open fun drawRandomness(): LongArray {
        val random = ThreadLocalRandom.current()
        return longArrayOf(random.nextLong(), random.nextLong())
    }

    private companion object {
        private const val RANDOM_HI_MASK = 0xFFFFL // 16 bits
    }
}
