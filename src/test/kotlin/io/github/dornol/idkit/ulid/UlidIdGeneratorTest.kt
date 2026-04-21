package io.github.dornol.idkit.ulid

import io.github.dornol.idkit.testutil.collectConcurrently
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
    fun `same ms produces strictly increasing ulids via counter increment`() {
        // Use a fake clock locked to a single ms to force the monotonic increment path.
        val gen = object : UlidIdGenerator() {
            override fun currentEpochMillis(): Long = 1_700_000_000_000L
        }
        var prev = gen.nextId()
        repeat(10_000) {
            val curr = gen.nextId()
            assertTrue(curr > prev, "same-ms ULIDs must strictly increase: '$prev' → '$curr'")
            assertEquals(prev.substring(0, 10), curr.substring(0, 10), "timestamp prefix must be identical")
            prev = curr
        }
    }

    @Test
    fun `clock regression preserves monotonicity`() {
        val fakeNow = AtomicLong(1_700_000_000_000L)
        val gen = object : UlidIdGenerator() {
            override fun currentEpochMillis(): Long = fakeNow.get()
        }

        val ulid1 = gen.nextId()
        fakeNow.addAndGet(-60_000L) // clock jumps 1 minute backward
        val ulid2 = gen.nextId()

        assertTrue(ulid2 > ulid1, "ULID must stay monotonic across clock regression: '$ulid1' → '$ulid2'")
        // Same-ms path should be used: timestamp prefix identical
        assertEquals(ulid1.substring(0, 10), ulid2.substring(0, 10))
    }

    @Test
    fun `concurrent generation yields unique ulids`() {
        val threads = 8
        val perThread = 10_000
        val ulids = collectConcurrently(threads, perThread) { generator.nextId() }
        assertEquals(threads * perThread, ulids.size)
    }

    @Test
    fun `throws on 80-bit randomness overflow within same millisecond`() {
        // Override the randomness seam so the first nextId() starts with a saturated
        // 80-bit value; the second same-ms call increments into overflow and must throw.
        val gen = object : UlidIdGenerator() {
            override fun currentEpochMillis(): Long = 1_700_000_000_000L
            override fun drawRandomness(): LongArray = longArrayOf(0xFFFFL, -1L)
        }
        gen.nextId() // seeds state at the 80-bit saturation point
        assertThrows<IllegalStateException> { gen.nextId() }
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

    @Test
    fun `nextId is not null`() {
        // Sanity check for the public API contract.
        assertNotNull(generator.nextId())
    }
}
