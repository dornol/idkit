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
 * Recording listener that counts every callback and the last observed `driftMillis`.
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
    fun `Snowflake fires onClockRegression with drift scaled to ms when timestampDivisor larger than 1`() {
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
    fun `Flake fires onSequenceOverflow when the per-ms sequence exhausts`() {
        // Use tiny sequence bits so we can exhaust in a single ms without needing millions of ids.
        // sequenceBits = 64 - 1 - 20 - 3 - 3 = 37 — still huge. Instead, maximize timestamp bits.
        // layout: 1 unused + 50 ts + 5 dc + 5 worker = 61 → sequence = 3 bits = max 7 ids/slice
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
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
        // sequenceBits = 64 - 1 - 50 - 5 - 5 = 3 → 8 ids per slice (0..7)
        assertEquals(3, gen.sequenceBits)

        // Fire 8 ids in the same slice — the 9th triggers overflow.
        // But the 9th call would busy-spin forever since the clock is frozen. So advance after
        // registering overflow: we race the listener + advance on a separate thread.
        val ids = mutableListOf<Long>()
        repeat(8) { ids += gen.nextId() }

        val t = Thread {
            // Small delay, then advance the clock so waitForNextSlice exits.
            Thread.sleep(50)
            clock.advance(1L)
        }
        t.start()
        ids += gen.nextId()
        t.join()

        assertEquals(9, ids.size)
        assertEquals(1, listener.sequenceOverflows.get())
    }

    // --- UUID v7 --------------------------------------------------------------------------------

    @Test
    fun `UUID v7 fires onCounterBorrow when the 12-bit counter exhausts within one ms`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val listener = RecordingListener()
        val gen = UuidV7IdGenerator(clock = clock, listener = listener)

        // 4096 ids fit in one ms (counter 0..4095). The 4097th borrows.
        repeat(4097) { gen.nextId() }

        assertEquals(1, listener.counterBorrows.get())
        assertEquals(0, listener.clockRegressions.get())
    }

    @Test
    fun `UUID v7 fires onClockRegression on strict backwards, not on same-ms reuse`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val listener = RecordingListener()
        val gen = UuidV7IdGenerator(clock = clock, listener = listener)

        gen.nextId()                                // establishes prevTs
        gen.nextId()                                // same-ms reuse → NOT a regression
        assertEquals(0, listener.clockRegressions.get())

        clock.regress(Duration.ofSeconds(2))
        gen.nextId()                                // strict backwards → regression
        assertEquals(1, listener.clockRegressions.get())
        assertTrue(listener.lastDriftMillis > 0)
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

    // --- default behaviour ----------------------------------------------------------------------

    @Test
    fun `NOOP listener is the default and costs nothing observable`() {
        // Just verify the default wiring produces ids as before — no callbacks to observe.
        val flake = SnowflakeIdGenerator(workerId = 0, datacenterId = 0)
        val ulid = UlidIdGenerator()
        val uuid = UuidV7IdGenerator()
        // Three ids each — nothing should throw.
        repeat(3) {
            flake.nextId()
            ulid.nextId()
            uuid.nextId()
        }
    }
}
