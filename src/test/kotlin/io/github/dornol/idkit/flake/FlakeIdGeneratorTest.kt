package io.github.dornol.idkit.flake

import io.github.dornol.idkit.testutil.collectConcurrently
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
    fun `ids are non-decreasing and positive`() {
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
            assertTrue(id >= prev, "IDs must be non-decreasing: prev=$prev, id=$id")
            prev = id
        }
    }

    @Test
    fun `bit fields can be decoded and match configuration`() {
        // Use small timestampBits but move epoch close to 'now' to avoid overflow into sign bit
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
        val id = cfg.nextId()

        // Recompute shifts the same way as the implementation
        val timestampLeftShift = cfg.datacenterIdBits + cfg.workerIdBits + cfg.sequenceBits
        val datacenterIdLeftShift = cfg.workerIdBits + cfg.sequenceBits
        val workerIdLeftShift = cfg.sequenceBits

        val sequence = id and cfg.maxSequence
        val extractedWorker = (id shr workerIdLeftShift).toInt() and cfg.maxWorkerId
        val extractedDc = (id shr datacenterIdLeftShift).toInt() and cfg.maxDatacenterId
        val timestampPortion = id shr timestampLeftShift

        assertEquals(13, extractedWorker)
        assertEquals(7, extractedDc)
        assertTrue(sequence in 0..cfg.maxSequence)
        assertTrue(timestampPortion >= 0)
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
                // Also ensure non-decreasing across rollover
                assertTrue(id >= prevId, "ID should not decrease across rollover")
            }
            prevId = id
        }
        assertTrue(observedRollover, "Expected to observe a sequence rollover within time budget")
    }

    @Test
    fun `timestampDivisor and custom epochStart affect timestamp portion`() {
        val divisor = 10L
        val customEpoch = Instant.ofEpochMilli(System.currentTimeMillis() - 5_000) // 5 seconds ago
        val gen = FlakeIdGenerator(
            timestampBits = 41,
            datacenterIdBits = 5,
            workerIdBits = 5,
            timestampDivisor = divisor,
            epochStart = customEpoch,
            datacenterId = 0,
            workerId = 0,
        )
        val id = gen.nextId()

        val timestampLeftShift = gen.datacenterIdBits + gen.workerIdBits + gen.sequenceBits
        val tsPortion = id shr timestampLeftShift

        val nowDiv = System.currentTimeMillis() / divisor
        val epochDiv = customEpoch.toEpochMilli() / divisor
        val expectedLowerBound = (nowDiv - epochDiv) - 1 // allow small timing gap

        assertTrue(tsPortion >= expectedLowerBound, "Timestamp portion should reflect divisor and epoch")
    }

    @Test
    fun `throws ClockMovedBackwardsException when system clock regresses`() {
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
        gen.nextId()

        // Simulate clock moving backwards by 1 minute
        fakeNow.addAndGet(-60_000L)

        val ex = assertThrows<ClockMovedBackwardsException> { gen.nextId() }
        assertTrue(ex.driftAmount > 0, "driftAmount must be positive, was ${ex.driftAmount}")
        assertEquals(1L, ex.timestampDivisor)

        // After the exception, internal state must be untouched — once the clock catches up,
        // the same generator instance should produce valid ids again.
        fakeNow.addAndGet(120_000L) // move past original prime timestamp
        val recovered = gen.nextId()
        assertTrue(recovered > 0L)
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
    fun `waitForNextSlice throws ClockMovedBackwardsException when clock regresses during busy-spin`() {
        // Narrow race: nextId() passes its top-level regression check, then the sequence
        // overflows and we enter waitForNextSlice. If the clock regresses during the spin,
        // we must fail fast instead of waiting for the wall clock to catch up.
        //
        // Configure with sequenceBits = 1 (maxSequence = 1) so overflow triggers on the 3rd
        // call. Return a stable clock for the first 3 reads (one per nextId() top), then
        // regress on the 4th read (first iteration inside waitForNextSlice).
        val callCount = AtomicInteger(0)
        val gen = object : FlakeIdGenerator(
            timestampBits = 52,
            datacenterIdBits = 5,
            workerIdBits = 5,
            timestampDivisor = 1L,
            epochStart = Instant.EPOCH,
            datacenterId = 1,
            workerId = 1,
        ) {
            override fun currentEpochMillis(): Long {
                return if (callCount.incrementAndGet() <= 3) 1_000_000L else 999_999L
            }
        }
        assertEquals(1, gen.sequenceBits, "sequenceBits must be 1 to force overflow on 3rd call")

        gen.nextId() // call #1: slice=1_000_000, seq=0
        gen.nextId() // call #2: slice=1_000_000, seq=1 (maxed out)
        val ex = assertThrows<ClockMovedBackwardsException> { gen.nextId() }
        assertTrue(ex.driftAmount > 0, "driftAmount must be positive, was ${ex.driftAmount}")
    }

    @Test
    fun `constructor rejects timestampBits of zero`() {
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
    }

    @Test
    fun `concurrent generation yields unique ids`() {
        val threads = 4
        val perThread = 5_000
        val gen = FlakeIdGenerator(41, 5, 5, 1L, Instant.EPOCH, datacenterId = 2, workerId = 3)

        val ids = collectConcurrently(threads, perThread) { gen.nextId() }
        assertEquals(threads * perThread, ids.size)
    }
}
