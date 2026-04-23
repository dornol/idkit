package io.github.dornol.idkit.flake

import io.github.dornol.idkit.testing.TestClock
import io.github.dornol.idkit.testing.testFlakeIdGenerator
import io.github.dornol.idkit.testutil.RecordingListener
import io.github.dornol.idkit.testutil.collectConcurrently
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class FlakeIdGeneratorTest {

    @Test
    fun `constructor validates bit allocations and id ranges`() {
        // datacenterIdBits must be 1..5
        assertThrows<IllegalArgumentException> {
            FlakeIdGenerator(
                timestampBits = 41,
                datacenterIdBits = 0,
                workerIdBits = 5,
                timestampDivisor = 1L,
                epochStart = Instant.EPOCH,
                datacenterId = 0,
                workerId = 0,
            )
        }
        assertThrows<IllegalArgumentException> {
            FlakeIdGenerator(
                timestampBits = 41,
                datacenterIdBits = 6,
                workerIdBits = 5,
                timestampDivisor = 1L,
                epochStart = Instant.EPOCH,
                datacenterId = 0,
                workerId = 0,
            )
        }
        // workerIdBits must be > 0
        assertThrows<IllegalArgumentException> {
            FlakeIdGenerator(
                timestampBits = 41,
                datacenterIdBits = 5,
                workerIdBits = 0,
                timestampDivisor = 1L,
                epochStart = Instant.EPOCH,
                datacenterId = 0,
                workerId = 0,
            )
        }
        // workerIdBits upper bound is 31
        assertThrows<IllegalArgumentException> {
            FlakeIdGenerator(
                timestampBits = 20,
                datacenterIdBits = 5,
                workerIdBits = 32,
                timestampDivisor = 1L,
                epochStart = Instant.EPOCH,
                datacenterId = 0,
                workerId = 0,
            )
        }
        // Total bits must be <= 63 (excluding sign bit). Use too-large timestampBits to trigger.
        assertThrows<IllegalArgumentException> {
            FlakeIdGenerator(
                timestampBits = 60,
                datacenterIdBits = 3,
                workerIdBits = 3,
                timestampDivisor = 1L,
                epochStart = Instant.EPOCH,
                datacenterId = 0,
                workerId = 0,
            )
        }
        // timestampDivisor must be > 0
        assertThrows<IllegalArgumentException> {
            FlakeIdGenerator(
                timestampBits = 41,
                datacenterIdBits = 5,
                workerIdBits = 5,
                timestampDivisor = 0L,
                epochStart = Instant.EPOCH,
                datacenterId = 0,
                workerId = 0,
            )
        }
        assertThrows<IllegalArgumentException> {
            FlakeIdGenerator(
                timestampBits = 41,
                datacenterIdBits = 5,
                workerIdBits = 5,
                timestampDivisor = -1L,
                epochStart = Instant.EPOCH,
                datacenterId = 0,
                workerId = 0,
            )
        }

        // Range checks for worker/datacenter based on bit sizes
        run {
            val gen = FlakeIdGenerator(
                timestampBits = 41,
                datacenterIdBits = 5,
                workerIdBits = 5,
                timestampDivisor = 1L,
                epochStart = Instant.EPOCH,
                datacenterId = 31,
                workerId = 31,
            )
            assertEquals((1 shl 5) - 1, gen.maxDatacenterId)
            assertEquals((1 shl 5) - 1, gen.maxWorkerId)
            // Boundary params must also produce a usable generator.
            assertTrue(gen.nextId() > 0)
        }
        assertThrows<IllegalArgumentException> {
            FlakeIdGenerator(41, 5, 5, 1L, Instant.EPOCH, datacenterId = -1, workerId = 0)
        }
        assertThrows<IllegalArgumentException> {
            FlakeIdGenerator(41, 5, 5, 1L, Instant.EPOCH, datacenterId = 0, workerId = -1)
        }
        assertThrows<IllegalArgumentException> {
            FlakeIdGenerator(41, 5, 5, 1L, Instant.EPOCH, datacenterId = 32, workerId = 0)
        }
        assertThrows<IllegalArgumentException> {
            FlakeIdGenerator(41, 5, 5, 1L, Instant.EPOCH, datacenterId = 0, workerId = 32)
        }
    }

    @Test
    fun `ids are strictly increasing and positive`() {
        val gen = FlakeIdGenerator(
            timestampBits = 41,
            datacenterIdBits = 5,
            workerIdBits = 5,
            timestampDivisor = 1L,
            epochStart = Instant.EPOCH,
            datacenterId = 1,
            workerId = 1,
        )
        var prev = gen.nextId()
        assertTrue(prev > 0)
        repeat(50_000) {
            val id = gen.nextId()
            assertTrue(id > 0)
            // Single-generator, monotonic clock: ids must be STRICTLY increasing.
            assertTrue(id > prev, "IDs must be strictly increasing: prev=$prev, id=$id")
            prev = id
        }
    }

    @Test
    fun `bit fields decode to the configured worker datacenter, sequence, and non-decreasing timestamp`() {
        val recentEpoch = Instant.ofEpochMilli(System.currentTimeMillis() - 1_000)
        val cfg = FlakeIdGenerator(
            timestampBits = 40,
            datacenterIdBits = 5,
            workerIdBits = 5,
            timestampDivisor = 1L,
            epochStart = recentEpoch,
            datacenterId = 7,
            workerId = 13,
        )

        // Generate a short burst so the sequence counter is actually exercised
        // (single nextId() often leaves sequence at 0 which is a tautological check).
        val ids = (0 until 32).map { cfg.nextId() }

        val timestampLeftShift = cfg.datacenterIdBits + cfg.workerIdBits + cfg.sequenceBits
        val datacenterIdLeftShift = cfg.workerIdBits + cfg.sequenceBits
        val workerIdLeftShift = cfg.sequenceBits

        val sequences = ids.map { it and cfg.maxSequence }
        val workers = ids.map { (it shr workerIdLeftShift).toInt() and cfg.maxWorkerId }
        val dcs = ids.map { (it shr datacenterIdLeftShift).toInt() and cfg.maxDatacenterId }
        val tsPortions = ids.map { it shr timestampLeftShift }

        assertTrue(workers.all { it == 13 }, "all ids must encode workerId=13")
        assertTrue(dcs.all { it == 7 }, "all ids must encode datacenterId=7")

        // Sequence must span at least two distinct values across 32 ids in a tight loop; at
        // least one transition from N -> N+1 within the same ms must be observable.
        assertTrue(sequences.toSet().size >= 2, "sequence field must actually vary: got $sequences")
        // Timestamp portion is monotonic non-decreasing (ms granularity may repeat).
        for (i in 1 until tsPortions.size) {
            assertTrue(tsPortions[i] >= tsPortions[i - 1])
        }
    }

    @Test
    fun `sequence rollover waits for next time slice`() {
        // Configure to make sequenceBits very small to force quick rollover
        val gen = FlakeIdGenerator(
            timestampBits = 52,
            datacenterIdBits = 5,
            workerIdBits = 5,
            timestampDivisor = 1L,
            epochStart = Instant.EPOCH,
            datacenterId = 1,
            workerId = 1,
        )
        assertEquals(1, gen.sequenceBits, "Expected 1 sequence bit for this configuration")
        val maxSeq = gen.maxSequence // should be 1

        // Generate a tight loop of IDs and detect a rollover from sequence==max to 0
        var prevId = gen.nextId()
        var observedRollover = false
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && !observedRollover) {
            val id = gen.nextId()
            val prevSeq = prevId and maxSeq
            val seq = id and maxSeq
            // rollover when previous was max and current is 0
            if (prevSeq == maxSeq && seq == 0L) {
                observedRollover = true
                // Also ensure strict monotonicity across rollover (new slice means bigger id).
                assertTrue(id > prevId, "ID must strictly increase across rollover")
            }
            prevId = id
        }
        assertTrue(observedRollover, "Expected to observe a sequence rollover within time budget")
    }

    @Test
    fun `timestampDivisor and custom epochStart produce exact delta under a fixed clock`() {
        val divisor = 10L
        val epoch = Instant.ofEpochMilli(1_000_000L)
        val clock = TestClock(Instant.ofEpochMilli(1_005_000L)) // 5000 ms after epoch
        val gen = FlakeIdGenerator(
            timestampBits = 41,
            datacenterIdBits = 5,
            workerIdBits = 5,
            timestampDivisor = divisor,
            epochStart = epoch,
            datacenterId = 0,
            workerId = 0,
            clock = clock,
        )
        val id = gen.nextId()
        val timestampLeftShift = gen.datacenterIdBits + gen.workerIdBits + gen.sequenceBits
        val tsPortion = id shr timestampLeftShift

        // Precise formula: (now - epoch) / divisor = 5000 / 10 = 500
        assertEquals(500L, tsPortion, "timestamp portion must equal (now-epoch)/divisor exactly")
    }

    @Test
    fun `regression exceeding tolerance still throws with descriptive state`() {
        // 1 minute of regression is far above any plausible tolerance, so this covers both
        // strict mode and the default tolerant mode. driftAmount is expressed in the same
        // timestamp-unit semantics regardless of mode.
        val fakeNow = AtomicLong(System.currentTimeMillis())
        val gen = object : FlakeIdGenerator(
            timestampBits = 41,
            datacenterIdBits = 5,
            workerIdBits = 5,
            timestampDivisor = 1L,
            epochStart = Instant.EPOCH,
            datacenterId = 1,
            workerId = 1,
        ) {
            override fun currentEpochMillis(): Long = fakeNow.get()
        }

        // prime the generator so `lastGeneratedTimestamp` is populated
        val primed = gen.nextId()

        // Simulate clock moving backwards by 1 minute — far beyond the 10 ms default tolerance.
        fakeNow.addAndGet(-60_000L)

        val ex = assertThrows<ClockMovedBackwardsException> { gen.nextId() }
        assertEquals(60_000L, ex.driftAmount, "driftAmount must equal the regressed ms count")
        assertEquals(1L, ex.timestampDivisor)
        assertTrue(
            (ex.message ?: "").contains("backwards", ignoreCase = true),
            "message should describe the regression: got '${ex.message}'",
        )

        // After the exception, internal state must be untouched — once the clock catches up,
        // the same generator instance must produce strictly greater ids than before.
        fakeNow.addAndGet(120_000L) // move 1 minute past the original prime time
        val recovered = gen.nextId()
        assertTrue(
            recovered > primed,
            "recovered id must be > primed id (generator state intact): primed=$primed, recovered=$recovered",
        )
    }

    @Test
    fun `default tolerance absorbs regressions up to 10 ms and keeps ids strictly increasing`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val listener = RecordingListener()
        val gen = testFlakeIdGenerator(clock = clock, listener = listener)

        val primed = gen.nextId()
        // Regress 5 ms — well inside the 10 ms default tolerance. Must NOT throw.
        clock.regress(5L)
        val afterAbsorb = gen.nextId()

        assertTrue(
            afterAbsorb > primed,
            "absorbed regression must still yield a strictly greater id: primed=$primed, after=$afterAbsorb",
        )
        assertEquals(1L, listener.clockRegressions.get(), "listener must observe the absorbed regression")
        assertEquals(5L, listener.lastDriftMillis, "drift reported in ms must match the clock regression")
    }

    @Test
    fun `default tolerance throws when regression exceeds 10 ms`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = testFlakeIdGenerator(clock = clock)
        gen.nextId()

        // 11 ms — one past the default tolerance. Must throw.
        clock.regress(11L)
        val ex = assertThrows<ClockMovedBackwardsException> { gen.nextId() }
        assertEquals(11L, ex.driftAmount)
    }

    @Test
    fun `explicit tolerance of ZERO preserves strict fail-fast on any backwards movement`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = testFlakeIdGenerator(clock = clock, clockRegressionTolerance = Duration.ZERO)
        gen.nextId()

        // Even a single-ms regression must throw under strict mode.
        clock.regress(1L)
        val ex = assertThrows<ClockMovedBackwardsException> { gen.nextId() }
        assertEquals(1L, ex.driftAmount)
    }

    @Test
    fun `larger explicit tolerance absorbs correspondingly larger regressions`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = testFlakeIdGenerator(
            clock = clock,
            clockRegressionTolerance = Duration.ofMillis(100),
        )
        gen.nextId()

        clock.regress(80L) // inside the 100 ms tolerance
        val absorbed = gen.nextId()
        assertTrue(absorbed > 0)

        clock.regress(100L) // accumulated drift now far beyond tolerance
        assertThrows<ClockMovedBackwardsException> { gen.nextId() }
    }

    @Test
    fun `sequence borrow under pinned clock stays monotonic until tolerance is exhausted`() {
        // sequenceBits = 3 → 8 ids per slice. With TestClock held fixed, every 8th call
        // forces a sequence overflow; tolerant mode borrows one slice per overflow.
        // Tolerance = 3 ms allows 3 borrows before the 4th throws.
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = testFlakeIdGenerator(
            clock = clock,
            timestampBits = 50,
            clockRegressionTolerance = Duration.ofMillis(3),
        )
        assertEquals(3, gen.sequenceBits, "sequenceBits must be 3 so overflow happens every 8 ids")

        // 8 ids: fills sequence 0..7 at slice T.
        val ids = mutableListOf<Long>()
        repeat(8) { ids += gen.nextId() }
        // Next 3 ids borrow slices T+1, T+2, T+3 — each uses only seq=0 then we immediately
        // call again within the same wall-clock ms to re-enter the borrow path.
        repeat(3) {
            // Fill the borrowed slice (8 ids) so the next call needs another borrow.
            ids += gen.nextId() // seq=0 at the newly borrowed slice
            repeat(7) { ids += gen.nextId() } // seq 1..7 at the same borrowed slice
        }
        // Monotonicity must hold across every borrow.
        for (i in 1 until ids.size) {
            assertTrue(ids[i] > ids[i - 1], "ids must strictly increase across borrows: i=$i, prev=${ids[i - 1]}, cur=${ids[i]}")
        }

        // The 4th borrow would advance the internal timestamp to T+4, drift = 4 ms > tolerance.
        assertThrows<ClockMovedBackwardsException> { gen.nextId() }
    }

    @Test
    fun `constructor rejects negative clockRegressionTolerance`() {
        assertThrows<IllegalArgumentException> {
            FlakeIdGenerator(
                timestampBits = 41,
                datacenterIdBits = 5,
                workerIdBits = 5,
                datacenterId = 0,
                workerId = 0,
                clockRegressionTolerance = Duration.ofMillis(-1),
            )
        }
    }

    @Test
    fun `timestamp delta uses precise (nowMillis - epochMillis) division`() {
        // With divisor=10 and epoch not aligned to divisor, the legacy `now/10 - epoch/10`
        // formula produced a value that differed from the precise `(now - epoch)/10` by ±1.
        //
        //   now=1060, epoch=1003, divisor=10
        //     legacy:  1060/10 - 1003/10 = 106 - 100 = 6
        //     precise: (1060 - 1003)/10  = 57/10    = 5
        val divisor = 10L
        val epoch = Instant.ofEpochMilli(1003L)
        val fakeNow = AtomicLong(1060L)
        val gen = object : FlakeIdGenerator(
            timestampBits = 41,
            datacenterIdBits = 5,
            workerIdBits = 5,
            timestampDivisor = divisor,
            epochStart = epoch,
            datacenterId = 0,
            workerId = 0,
        ) {
            override fun currentEpochMillis(): Long = fakeNow.get()
        }

        val id = gen.nextId()
        val timestampLeftShift = gen.datacenterIdBits + gen.workerIdBits + gen.sequenceBits
        val actualDelta = id shr timestampLeftShift

        assertEquals(5L, actualDelta, "Expected precise (1060-1003)/10 = 5, got $actualDelta")
    }

    @Test
    fun `strict mode waitForNextSlice throws ClockMovedBackwardsException when clock regresses during busy-spin`() {
        // Narrow race: nextId() passes its top-level regression check, then the sequence
        // overflows and we enter advanceToNextSlice. Under strict mode (tolerance = ZERO) a
        // regression during the spin must fail fast instead of waiting for the wall clock to
        // catch up.
        //
        // Configure with sequenceBits = 1 (maxSequence = 1) so overflow triggers on the 3rd
        // call. Return a stable clock for the first 3 reads (one per nextId() top), then
        // regress on the 4th read (first iteration inside advanceToNextSlice).
        val callCount = AtomicInteger(0)
        val gen = object : FlakeIdGenerator(
            timestampBits = 52,
            datacenterIdBits = 5,
            workerIdBits = 5,
            timestampDivisor = 1L,
            epochStart = Instant.EPOCH,
            datacenterId = 1,
            workerId = 1,
            clockRegressionTolerance = Duration.ZERO,
        ) {
            override fun currentEpochMillis(): Long {
                return if (callCount.incrementAndGet() <= 3) 1_000_000L else 999_999L
            }
        }
        assertEquals(1, gen.sequenceBits, "sequenceBits must be 1 to force overflow on 3rd call")

        gen.nextId() // call #1: slice=1_000_000, seq=0
        gen.nextId() // call #2: slice=1_000_000, seq=1 (maxed out)
        val ex = assertThrows<ClockMovedBackwardsException> { gen.nextId() }
        // driftAmount is the drift in SLICES; divisor=1 so slices == ms here.
        assertEquals(1L, ex.driftAmount)
        assertEquals(1L, ex.timestampDivisor)
    }

    @Test
    fun `constructor rejects timestampBits of zero and negative`() {
        assertThrows<IllegalArgumentException> {
            FlakeIdGenerator(
                timestampBits = 0,
                datacenterIdBits = 5,
                workerIdBits = 5,
                timestampDivisor = 1L,
                epochStart = Instant.EPOCH,
                datacenterId = 0,
                workerId = 0,
            )
        }
        assertThrows<IllegalArgumentException> {
            FlakeIdGenerator(
                timestampBits = -1,
                datacenterIdBits = 5,
                workerIdBits = 5,
                timestampDivisor = 1L,
                epochStart = Instant.EPOCH,
                datacenterId = 0,
                workerId = 0,
            )
        }
    }

    @Test
    fun `throws IllegalStateException when the timestamp field overflows its bit budget`() {
        // Configure a tiny timestampBits field and move the clock far enough past epoch that
        // the computed delta does not fit. maxTimestamp = (1 shl 10) - 1 = 1023 slices.
        val clock = TestClock(Instant.ofEpochMilli(2_000)) // 2000 ms past epoch
        val gen = FlakeIdGenerator(
            timestampBits = 10,
            datacenterIdBits = 5,
            workerIdBits = 5,
            timestampDivisor = 1L,
            epochStart = Instant.EPOCH,
            datacenterId = 0,
            workerId = 0,
            clock = clock,
        )
        val ex = assertThrows<IllegalStateException> { gen.nextId() }
        assertTrue(
            (ex.message ?: "").contains("overflow", ignoreCase = true),
            "message should describe the overflow: got '${ex.message}'",
        )
    }

    @Test
    fun `concurrent generation yields unique, strictly increasing ids per thread`() {
        val threads = 4
        val perThread = 5_000
        val gen = FlakeIdGenerator(41, 5, 5, 1L, Instant.EPOCH, datacenterId = 2, workerId = 3)

        val ids = collectConcurrently(threads, perThread) { gen.nextId() }
        assertEquals(threads * perThread, ids.size)
        // Every id must be positive (no sign-bit corruption under contention).
        assertTrue(ids.all { it > 0L }, "all concurrent ids must be positive")
        // Worker and datacenter bits must decode correctly under contention.
        val workerShift = gen.sequenceBits
        val dcShift = gen.workerIdBits + gen.sequenceBits
        assertTrue(
            ids.all { ((it shr workerShift).toInt() and gen.maxWorkerId) == 3 },
            "workerId must decode to 3 for every concurrent id",
        )
        assertTrue(
            ids.all { ((it shr dcShift).toInt() and gen.maxDatacenterId) == 2 },
            "datacenterId must decode to 2 for every concurrent id",
        )
    }

    @Test
    fun `nextIds batch produces strictly increasing ids and respects count semantics`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = FlakeIdGenerator(
            timestampBits = 41,
            datacenterIdBits = 5,
            workerIdBits = 5,
            datacenterId = 0,
            workerId = 0,
            clock = clock,
        )
        val parser = FlakeIdParser.of(gen)

        assertEquals(emptyList<Long>(), gen.nextIds(0))
        assertThrows<IllegalArgumentException> { gen.nextIds(-1) }

        val batch = gen.nextIds(200)
        assertEquals(200, batch.size)
        for (i in 1 until batch.size) {
            assertTrue(batch[i] > batch[i - 1])
        }
        // Under a pinned clock, all ids share the same embedded timestamp and their sequence
        // counter walks 0..N-1.
        val expectedTs = clock.nowInstant()
        batch.forEachIndexed { i, id ->
            assertEquals(expectedTs, parser.timestampOf(id), "ts must stay pinned under fixed clock")
            assertEquals(i.toLong(), parser.sequenceOf(id), "sequence must equal batch index $i")
        }
    }
}
