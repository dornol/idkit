package io.github.dornol.idkit.ulid

import io.github.dornol.idkit.IdGenerator
import java.util.concurrent.ThreadLocalRandom

/**
 * Crockford's Base32 alphabet — excludes `I`, `L`, `O`, `U` to avoid visual ambiguity.
 * `internal` so the test suite can assert against the single source of truth without
 * duplicating the 32-char literal.
 */
internal const val CROCKFORD_BASE32_ALPHABET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

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
open class UlidIdGenerator : IdGenerator<String> {

    /** Last observed timestamp in ms. `-1` means uninitialized. */
    private var lastTimestamp: Long = -1L

    /** High 16 bits of the 80-bit randomness (stored in the low bits of a Long). */
    private var randomHi: Long = 0L

    /** Low 64 bits of the 80-bit randomness. */
    private var randomLo: Long = 0L

    @Synchronized
    final override fun nextId(): String {
        val now = currentEpochMillis()

        if (now > lastTimestamp) {
            // New ms slice — roll a fresh 80 bits of randomness.
            lastTimestamp = now
            val seed = drawRandomness()
            // Mask enforces the 16-bit contract even if an override returns wider values.
            randomHi = seed[0] and RANDOM_HI_MASK
            randomLo = seed[1]
        } else {
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

        return encode(lastTimestamp, randomHi, randomLo)
    }

    /**
     * Returns the current wall-clock epoch milliseconds.
     *
     * Exposed as `protected open` so tests can inject a fake clock. Do not override in
     * production code.
     */
    protected open fun currentEpochMillis(): Long = System.currentTimeMillis()

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
        private const val MASK_5_BITS = 0x1FL
        private const val RANDOM_HI_MASK = 0xFFFFL // 16 bits

        /**
         * Encodes `(timestamp, randomHi, randomLo)` into a 26-char Crockford Base32 string.
         *
         * Bit layout (80-bit randomness emitted from LSB toward MSB):
         *  - chars 25..14 (12 chars) draw from the low 60 bits of [randomLo]
         *  - char 13 straddles: 4 bits from [randomLo] (bits 60..63) + 1 bit from [randomHi] (bit 0)
         *  - chars 12..10 (3 chars) draw from the remaining 15 bits of [randomHi] (bits 1..15)
         */
        private fun encode(timestamp: Long, randomHi: Long, randomLo: Long): String {
            val chars = CharArray(26)

            // chars 0..9: 48-bit timestamp.
            var ts = timestamp
            for (i in 9 downTo 0) {
                chars[i] = CROCKFORD_BASE32_ALPHABET[(ts and MASK_5_BITS).toInt()]
                ts = ts ushr 5
            }

            // chars 25..14: 12 chars from randomLo's low 60 bits. After the loop, `rl` holds
            // only the top 4 bits of the original randomLo (bits 60..63).
            var rl = randomLo
            for (i in 25 downTo 14) {
                chars[i] = CROCKFORD_BASE32_ALPHABET[(rl and MASK_5_BITS).toInt()]
                rl = rl ushr 5
            }

            // char 13: the remaining 4 bits of randomLo + 1 low bit of randomHi. The `rl and
            // 0xFL` mask is defensive — `rl` should already fit in 4 bits after the loop above.
            var rh = randomHi
            val char13 = ((rh and 0x1L) shl 4) or (rl and 0xFL)
            chars[13] = CROCKFORD_BASE32_ALPHABET[char13.toInt()]
            rh = rh ushr 1

            // chars 12..10: 3 chars from randomHi's remaining 15 bits.
            for (i in 12 downTo 10) {
                chars[i] = CROCKFORD_BASE32_ALPHABET[(rh and MASK_5_BITS).toInt()]
                rh = rh ushr 5
            }

            return String(chars)
        }
    }
}
