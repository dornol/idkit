package io.github.dornol.idkit.ulid

import io.github.dornol.idkit.testing.TestClock
import io.github.dornol.idkit.testing.deterministicUlidIdGenerator
import io.github.dornol.idkit.testutil.collectConcurrently
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class UlidIdGeneratorTest {

    private lateinit var generator: UlidIdGenerator

    @BeforeEach
    fun setUp() {
        generator = UlidIdGenerator()
    }

    @Test
    fun `ulid is 26 characters long`() {
        repeat(1_000) {
            val ulid = generator.nextId()
            assertEquals(26, ulid.length, "ULID must be 26 chars, got '$ulid'")
        }
    }

    @Test
    fun `ulid uses only Crockford Base32 alphabet`() {
        val allowed = CROCKFORD_BASE32_ALPHABET.toSet()
        repeat(10_000) {
            val ulid = generator.nextId()
            ulid.forEach { c ->
                assertTrue(c in allowed, "Char '$c' in '$ulid' is not Crockford Base32")
            }
        }
    }

    @Test
    fun `timestamp portion reflects current system time`() {
        val before = System.currentTimeMillis()
        val ulid = generator.nextId()
        val after = System.currentTimeMillis()

        // Decode the first 10 chars back into a 48-bit timestamp.
        val ts = decodeTimestamp(ulid)
        assertTrue(ts in before..after, "decoded ts=$ts not in [$before..$after]")
    }

    @Test
    fun `timestamp portion exactly tracks clock advance`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = UlidIdGenerator(clock = clock)
        val u1 = gen.nextId()
        clock.advance(Duration.ofMillis(42))
        val u2 = gen.nextId()
        assertEquals(
            42L,
            decodeTimestamp(u2) - decodeTimestamp(u1),
            "ts delta must equal the clock advance amount",
        )
    }

    @Test
    fun `tight loop produces strictly increasing ulids`() {
        // Monotonicity within a millisecond: the 80-bit randomness increments by 1, so the
        // emitted string must be lexicographically greater than the previous one.
        val count = 100_000
        var prev = generator.nextId()
        repeat(count) { i ->
            val curr = generator.nextId()
            assertTrue(curr > prev, "ULIDs must be strictly increasing at $i: prev='$prev', curr='$curr'")
            prev = curr
        }
    }

    @Test
    fun `same ms produces strictly increasing ulids via +1 randomness increment`() {
        // Pin the clock AND the initial randomness seed (all-zero) so successive ULIDs in the
        // same ms differ by exactly +1 in the 80-bit randomness. This pins the ULID spec's
        // monotonic profile, not just "strictly increasing".
        val gen = deterministicUlidIdGenerator(TestClock(Instant.parse("2024-01-15T00:00:00Z")))
        repeat(10_000) { i ->
            val u = gen.nextId()
            val (hi, lo) = decodeRandomness(u)
            // For this deterministic seed, the 80-bit randomness == iteration index i.
            assertEquals(i.toLong(), lo, "low 64 bits must equal iteration index under deterministic seed")
            assertEquals(0L, hi, "high 16 bits must remain zero until the low 64 bits wrap")
        }
    }

    @Test
    fun `clock regression preserves monotonicity and does not advance timestamp prefix`() {
        val fakeNow = AtomicLong(1_700_000_000_000L)
        val gen = object : UlidIdGenerator() {
            override fun currentEpochMillis(): Long = fakeNow.get()
        }

        val ulid1 = gen.nextId()
        fakeNow.addAndGet(-60_000L) // clock jumps 1 minute backward
        val ulid2 = gen.nextId()

        assertTrue(ulid2 > ulid1, "ULID must stay monotonic across clock regression: '$ulid1' → '$ulid2'")
        // Same-ms path should be used: timestamp prefix identical (regression does NOT advance).
        assertEquals(ulid1.substring(0, 10), ulid2.substring(0, 10))
    }

    @Test
    fun `concurrent generation yields unique ulids`() {
        val threads = 8
        val perThread = 10_000
        val ulids = collectConcurrently(threads, perThread) { generator.nextId() }
        assertEquals(threads * perThread, ulids.size)
        // Every concurrently-produced id must also be a valid ULID string.
        ulids.forEach { assertTrue(UlidParser.isValid(it), "concurrent output '$it' not valid") }
    }

    @Test
    fun `throws IllegalStateException on 80-bit randomness overflow within same millisecond`() {
        // Override the randomness seam so the first nextId() starts with a saturated
        // 80-bit value; the second same-ms call increments into overflow and must throw.
        val gen = object : UlidIdGenerator() {
            override fun currentEpochMillis(): Long = 1_700_000_000_000L
            override fun drawRandomness(): LongArray = longArrayOf(0xFFFFL, -1L)
        }
        gen.nextId() // seeds state at the 80-bit saturation point
        val ex = assertThrows<IllegalStateException> { gen.nextId() }
        assertTrue(
            (ex.message ?: "").contains("overflow"),
            "exception message should mention overflow: got '${ex.message}'",
        )
    }

    @Test
    fun `ulid is lexicographically sortable by timestamp`() {
        val fakeNow = AtomicLong(1_700_000_000_000L)
        val gen = object : UlidIdGenerator() {
            override fun currentEpochMillis(): Long = fakeNow.get()
        }

        val early = gen.nextId()
        fakeNow.addAndGet(1_000_000L) // jump forward 1000 seconds
        val late = gen.nextId()

        assertTrue(late > early, "later-timestamp ULID must sort after earlier: '$early' vs '$late'")
    }

    @Test
    fun `nextIds holds intra-ms monotonicity across the batch`() {
        val gen = deterministicUlidIdGenerator(TestClock(Instant.parse("2024-01-15T00:00:00Z")))
        val batch = gen.nextIds(500)
        assertEquals(500, batch.size)
        for (i in 1 until batch.size) {
            assertTrue(batch[i] > batch[i - 1], "batch[$i] must be > batch[${i - 1}]")
            assertEquals(
                batch[i - 1].substring(0, 10), batch[i].substring(0, 10),
                "batch timestamp prefix must stay pinned (same ms)",
            )
        }
    }

    // ---- helpers ----

    private fun decodeTimestamp(ulid: String): Long {
        // Decode the first 10 Crockford Base32 chars into a 48-bit timestamp.
        var ts = 0L
        for (i in 0 until 10) {
            val v = CROCKFORD_BASE32_ALPHABET.indexOf(ulid[i])
            assertNotEquals(-1, v, "char '${ulid[i]}' is not in the Crockford alphabet")
            ts = (ts shl 5) or v.toLong()
        }
        return ts
    }

    /** Decodes the 16-char randomness suffix into (hi16, lo64). */
    private fun decodeRandomness(ulid: String): Pair<Long, Long> {
        // 16 chars × 5 bits each = 80 bits. Top 16 bits are hi; bottom 64 bits are lo.
        var hi = 0L
        var lo = 0L
        for (i in 10 until 26) {
            val v = CROCKFORD_BASE32_ALPHABET.indexOf(ulid[i]).toLong()
            // Shift in from the right. For 80 bits total: [hi:16][lo:64]
            val combinedShift = (25 - i) * 5
            if (combinedShift >= 64) {
                hi = hi or (v shl (combinedShift - 64))
            } else {
                lo = lo or (v shl combinedShift)
                // Bits that overflow into hi on a partial shift
                val overflow = 64 - combinedShift
                if (overflow < 5) {
                    hi = hi or (v ushr overflow)
                }
            }
        }
        return hi to lo
    }
}
