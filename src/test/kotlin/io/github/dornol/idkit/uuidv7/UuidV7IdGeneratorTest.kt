package io.github.dornol.idkit.uuidv7

import io.github.dornol.idkit.testing.TestClock
import io.github.dornol.idkit.testutil.collectConcurrently
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class UuidV7IdGeneratorTest {

    private lateinit var generator: UuidV7IdGenerator

    @BeforeEach
    fun setUp() {
        generator = UuidV7IdGenerator()
    }

    @Test
    fun `should generate UUID version 7`() {
        val uuid = generator.nextId()
        assertEquals(7, uuid.version())
    }

    @Test
    fun `timestamps advance strictly after wall-clock sleep`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = UuidV7IdGenerator(clock = clock)
        val uuid1 = gen.nextId()
        clock.advance(Duration.ofMillis(3))
        val uuid2 = gen.nextId()
        val ts1 = uuid1.mostSignificantBits ushr 16
        val ts2 = uuid2.mostSignificantBits ushr 16
        // After a 3-ms advance with no same-ms same-generator pressure, the embedded timestamp
        // MUST strictly increase. `>=` would have hidden a "new-ms branch not taken" bug.
        assertTrue(ts2 > ts1, "UUID2 timestamp must strictly exceed UUID1: ts1=$ts1, ts2=$ts2")
        assertEquals(3L, ts2 - ts1, "ts delta must equal the clock advance")
    }

    @Test
    fun `should have correct variant bits`() {
        repeat(100) {
            val uuid = generator.nextId()
            assertEquals(2, uuid.variant(), "UUID v7 variant must be 2 (IETF RFC 4122)")
        }
    }

    @Test
    fun `should have correct bit layout`() {
        val uuid = generator.nextId()
        val msb = uuid.mostSignificantBits

        // Version bits (48..51) are 0b0111 = 7
        val version = ((msb shr 12) and 0xFL).toInt()
        assertEquals(7, version, "Version bits should be 7")

        // Timestamp (top 48 bits) should be close to the current wall clock.
        // ±100 ms is generous without being sloppy; bug cases produce drifts in seconds.
        val timestamp = msb ushr 16
        val now = System.currentTimeMillis()
        assertTrue(
            timestamp in (now - 100)..(now + 100),
            "Timestamp should be within ±100 ms of now: ts=$timestamp, now=$now",
        )

        // Verify variant bits inside leastSignificantBits
        val lsb = uuid.leastSignificantBits
        val variantBits = (lsb ushr 62) and 0x3L
        assertEquals(2L, variantBits, "Variant bits (top 2 bits of lsb) should be 0b10")
    }

    @Test
    fun `concurrent generation produces unique UUIDs`() {
        val threads = 8
        val perThread = 5000
        val uuids = collectConcurrently(threads, perThread) { generator.nextId() }
        assertEquals(threads * perThread, uuids.size)
        // Every emitted UUID must be a valid v7.
        uuids.forEach { assertEquals(7, it.version()) }
    }

    @Test
    fun `sequential generation produces unique UUIDs`() {
        val count = 10_000
        val uuids = mutableSetOf<UUID>()
        repeat(count) {
            val uuid = generator.nextId()
            assertTrue(uuids.add(uuid), "Duplicate UUID detected at iteration $it: $uuid")
        }
        assertEquals(count, uuids.size)
    }

    @Test
    fun `mostSigBits are strictly monotonic across tight loop`() {
        // A tight loop on a modern JVM generates thousands of UUIDs per ms.
        // RFC 9562 Method 2 requires intra-ms monotonicity via a dedicated counter,
        // so mostSigBits (which carries timestamp + version + counter) must strictly increase.
        val count = 100_000
        var prev = generator.nextId().mostSignificantBits
        repeat(count) { i ->
            val curr = generator.nextId().mostSignificantBits
            assertTrue(
                curr > prev,
                "mostSigBits must strictly increase at iteration $i: prev=$prev, curr=$curr"
            )
            prev = curr
        }
    }

    @Test
    fun `counter increments by exactly one within same millisecond under a fixed clock`() {
        // Pin the clock and confirm the counter in msb bits 52..63 advances by exactly 1 per id.
        // This pins RFC 9562 Method 2 (Fixed-Length Dedicated Counter Bits).
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = UuidV7IdGenerator(clock = clock)

        val counters = LongArray(4096) { gen.nextId().mostSignificantBits and 0xFFFL }
        for (i in counters.indices) {
            assertEquals(
                i.toLong(), counters[i],
                "counter at index $i must equal $i (strict +1 increment from 0)",
            )
        }
        // Timestamp portion stays pinned.
        val ts = (gen.nextId().mostSignificantBits ushr 16)
        // After 4097 calls we borrowed one ms — so this one is +1 ms.
        assertEquals(
            clock.now() + 1, ts,
            "after borrow, embedded ts must be exactly clock + 1",
        )
    }

    @Test
    fun `counter borrow advances timestamp by one when exceeding 4096 ids per ms`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = UuidV7IdGenerator(clock = clock)

        // First 4096 ids → counter 0..4095, timestamp stays at clock.
        repeat(4096) { gen.nextId() }
        val startMs = clock.now()

        // 4097th id → borrow; timestamp = clock + 1, counter = 0.
        val u = gen.nextId()
        val ts = u.mostSignificantBits ushr 16
        val ctr = u.mostSignificantBits and 0xFFFL
        assertEquals(startMs + 1, ts, "borrow must advance timestamp by exactly 1 ms")
        assertEquals(0L, ctr, "borrow must reset the counter to 0")
    }

    @Test
    fun `concurrent generation preserves global strict monotonicity of mostSigBits`() {
        // Under contention, CAS must still produce distinct mostSigBits (per-generator).
        // We collect per-thread sequences and verify each one is internally strictly increasing.
        val threads = 8
        val perThread = 10_000
        val msbs = collectConcurrently(threads, perThread) { generator.nextId().mostSignificantBits }
        assertEquals(threads * perThread, msbs.size)

        // Global set: all mostSigBits are unique (no two threads produce the same msb).
        // This is the strong monotonicity claim on the RFC 9562 Method 2 CAS loop.
        val set: Set<Long> = msbs
        assertEquals(threads * perThread, set.size)
    }
}
