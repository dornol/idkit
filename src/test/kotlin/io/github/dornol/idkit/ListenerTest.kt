package io.github.dornol.idkit

import io.github.dornol.idkit.flake.ClockMovedBackwardsException
import io.github.dornol.idkit.flake.FlakeIdGenerator
import io.github.dornol.idkit.flake.SnowflakeIdGenerator
import io.github.dornol.idkit.testing.TestClock
import io.github.dornol.idkit.ulid.UlidIdGenerator
import io.github.dornol.idkit.uuidv7.UuidV7IdGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Recording listener that counts every callback and stashes the most recent driftMillis.
 */
private class RecordingListener : IdGeneratorListener {
    val clockRegressions = AtomicLong(0L)
    val sequenceOverflows = AtomicLong(0L)
    val counterBorrows = AtomicLong(0L)
    @Volatile var lastDriftMillis: Long = -1L

    override fun onClockRegression(driftMillis: Long) {
        clockRegressions.incrementAndGet()
        lastDriftMillis = driftMillis
    }
    override fun onSequenceOverflow() {
        sequenceOverflows.incrementAndGet()
    }
    override fun onCounterBorrow() {
        counterBorrows.incrementAndGet()
    }
}

class ListenerTest {

    // --- Flake / Snowflake ---------------------------------------------------------------------

    @Test
    fun `Snowflake fires onClockRegression with drift in milliseconds`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val listener = RecordingListener()
        val gen = SnowflakeIdGenerator(
            workerId = 1, datacenterId = 2, clock = clock, listener = listener,
        )

        gen.nextId()
        clock.regress(Duration.ofSeconds(5))
        assertThrows(ClockMovedBackwardsException::class.java) { gen.nextId() }

        assertEquals(1, listener.clockRegressions.get())
        assertEquals(5_000L, listener.lastDriftMillis)
    }

    @Test
    fun `Flake with timestampDivisor scales drift back to real milliseconds`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val listener = RecordingListener()
        val gen = FlakeIdGenerator(
            timestampBits = 41,
            datacenterIdBits = 5,
            workerIdBits = 5,
            timestampDivisor = 10L,           // 10 ms per slice
            datacenterId = 0,
            workerId = 0,
            clock = clock,
            listener = listener,
        )

        gen.nextId()
        clock.regress(100L)                   // 10 slices
        assertThrows(ClockMovedBackwardsException::class.java) { gen.nextId() }

        assertEquals(1, listener.clockRegressions.get())
        assertEquals(100L, listener.lastDriftMillis) // 10 slices × 10 ms/slice = 100 ms drift
    }

    @Test
    fun `Flake fires onSequenceOverflow exactly once per exhaustion cycle`() {
        // Use a TestClock so we can drive sequence exhaustion AND the subsequent ms advance
        // deterministically. sequenceBits = 64 - 1 - 50 - 5 - 5 = 3 → 8 ids per slice.
        val clock = TestClock(1_000_000L)
        val listener = RecordingListener()
        val gen = FlakeIdGenerator(
            timestampBits = 50,
            datacenterIdBits = 5,
            workerIdBits = 5,
            datacenterId = 0,
            workerId = 0,
            clock = clock,
            listener = listener,
        )
        assertEquals(3, gen.sequenceBits)

        // 8 ids saturate the sequence at 0..7 within slice T.
        repeat(8) { gen.nextId() }
        assertEquals(0, listener.sequenceOverflows.get())

        // The 9th call enters waitForNextSlice → overflow fires once.
        val writer = Thread {
            Thread.sleep(50)
            clock.advance(1L)
        }
        writer.start()
        gen.nextId() // this call triggers the overflow path and spins waiting for advance
        writer.join()
        assertEquals(1, listener.sequenceOverflows.get(), "one overflow per exhaustion cycle")

        // One more id in the new slice must NOT refire overflow.
        gen.nextId()
        assertEquals(1, listener.sequenceOverflows.get(), "count must stay at 1")
    }

    // --- UUID v7 --------------------------------------------------------------------------------

    @Test
    fun `UUID v7 fires onCounterBorrow once per exhaustion at ids 4097 and 8193 under fixed clock`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val listener = RecordingListener()
        val gen = UuidV7IdGenerator(clock = clock, listener = listener)

        // First 4096 ids fill the counter 0..4095 — no borrow yet.
        repeat(4096) { gen.nextId() }
        assertEquals(0, listener.counterBorrows.get())

        // 4097th borrows once.
        gen.nextId()
        assertEquals(1, listener.counterBorrows.get())

        // Next 4095 ids fill the borrowed ms slice, no further borrow.
        repeat(4095) { gen.nextId() }
        assertEquals(1, listener.counterBorrows.get())

        // The 8193rd overall id (4097th within the borrowed slice) triggers the second borrow.
        gen.nextId()
        assertEquals(2, listener.counterBorrows.get())
    }

    @Test
    fun `UUID v7 fires onClockRegression on strict backwards with exact driftMillis`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val listener = RecordingListener()
        val gen = UuidV7IdGenerator(clock = clock, listener = listener)

        gen.nextId()                                // establishes prevTs at T
        gen.nextId()                                // same-ms reuse → NOT a regression
        assertEquals(0, listener.clockRegressions.get())

        clock.regress(Duration.ofSeconds(2))
        gen.nextId()                                // strict backwards → regression
        assertEquals(1, listener.clockRegressions.get())
        assertEquals(2_000L, listener.lastDriftMillis, "drift must equal exact regression in ms")
    }

    // --- ULID -----------------------------------------------------------------------------------

    @Test
    fun `ULID fires onClockRegression on strict backwards, not on same-ms reuse`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val listener = RecordingListener()
        val gen = UlidIdGenerator(clock = clock, listener = listener)

        gen.nextId()                                // establishes lastTimestamp
        gen.nextId()                                // same-ms reuse → NOT a regression
        assertEquals(0, listener.clockRegressions.get())

        clock.regress(Duration.ofSeconds(1))
        gen.nextId()                                // strict backwards → regression
        assertEquals(1, listener.clockRegressions.get())
        assertEquals(1_000L, listener.lastDriftMillis)
    }

    // --- listener exception propagation (per KDoc contract) -------------------------------------

    @Test
    fun `Flake listener that throws replaces the CMBE and leaves state intact for recovery`() {
        // KDoc: on Flake the listener fires BEFORE state commit. A thrown listener replaces
        // the CMBE but the generator stays in its pre-throw state — the next nextId() can
        // resume once the clock recovers.
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val throwingListener = object : IdGeneratorListener {
            override fun onClockRegression(driftMillis: Long) {
                throw RuntimeException("listener boom at drift=$driftMillis")
            }
        }
        val gen = SnowflakeIdGenerator(
            workerId = 0, datacenterId = 0, clock = clock, listener = throwingListener,
        )
        val primed = gen.nextId()
        clock.regress(Duration.ofSeconds(5))

        val ex = assertThrows(RuntimeException::class.java) { gen.nextId() }
        assertTrue(ex.message?.startsWith("listener boom") == true)

        // Recover: clock advances past the original prime → next call succeeds.
        clock.advance(Duration.ofSeconds(10))
        val recovered = gen.nextId()
        assertTrue(recovered > primed, "state must be intact after listener throw")
    }

    @Test
    fun `UUID v7 listener that throws propagates but CAS has already committed`() {
        // KDoc: on UUID v7, listener fires AFTER CAS commits. Thrown listener leaves the
        // generator in the post-commit state: caller loses that id, next call succeeds with
        // a strictly greater msb.
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val throwingListener = object : IdGeneratorListener {
            override fun onCounterBorrow() {
                throw RuntimeException("borrow boom")
            }
        }
        val gen = UuidV7IdGenerator(clock = clock, listener = throwingListener)

        // First 4096 ids don't borrow (counter 0..4095).
        val last = (1..4096).map { gen.nextId() }.last()
        // The 4097th triggers the borrow → listener throws, but the CAS committed first.
        assertThrows(RuntimeException::class.java) { gen.nextId() }

        // Generator state has already advanced; next call must succeed with strictly greater msb.
        val recovered = gen.nextId()
        assertTrue(
            recovered.mostSignificantBits > last.mostSignificantBits,
            "CAS commit before listener throw means state already advanced",
        )
    }
}
