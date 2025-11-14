package io.github.dornol.idkit.flake

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
            assertEquals((1L shl 5) - 1, gen.maxDatacenterId)
            assertEquals((1L shl 5) - 1, gen.maxWorkerId)
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
        val extractedWorker = (id shr workerIdLeftShift) and cfg.maxWorkerId
        val extractedDc = (id shr datacenterIdLeftShift) and cfg.maxDatacenterId
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
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
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
    fun `concurrent generation yields unique ids`() {
        val threads = 4
        val perThread = 5_000
        val gen = FlakeIdGenerator(41, 5, 5, 1L, Instant.EPOCH, datacenterId = 2, workerId = 3)

        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val set = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>(threads * perThread))

        repeat(threads) {
            pool.submit {
                try {
                    start.await()
                    repeat(perThread) {
                        val id = gen.nextId()
                        assertTrue(set.add(id), "Duplicate ID detected: $id")
                    }
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        val finished = done.await(30, TimeUnit.SECONDS)
        pool.shutdown()
        assertTrue(finished, "Workers did not finish in time")
        assertEquals(threads * perThread, set.size)
    }
}
