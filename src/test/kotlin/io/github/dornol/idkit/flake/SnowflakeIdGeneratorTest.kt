package io.github.dornol.idkit.flake

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.AbstractMap
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SnowflakeIdGeneratorTest {
    private val workerIdBits = 5
    private val datacenterIdBits = 5
    private val sequenceBits = 12
    private val maxWorkerId = (1L shl workerIdBits) - 1
    private val maxDatacenterId = (1L shl datacenterIdBits) - 1
    private val timestampLeftShift = datacenterIdBits + workerIdBits + sequenceBits
    private val datacenterIdLeftShift = workerIdBits + sequenceBits
    private val workerIdLeftShift = sequenceBits

    @Test
    fun `constructor rejects out of range workerId and dataCenterId`() {
        // workerId < 0
        assertThrows<IllegalArgumentException> {
            SnowflakeIdGenerator(-1, 0)
        }
        // workerId > MAX
        assertThrows<IllegalArgumentException> {
            SnowflakeIdGenerator(maxWorkerId + 1, 0)
        }
        // dataCenterId < 0
        assertThrows<IllegalArgumentException> {
            SnowflakeIdGenerator(0, -1)
        }
        // dataCenterId > MAX
        assertThrows<IllegalArgumentException> {
            SnowflakeIdGenerator(0, maxDatacenterId + 1)
        }
        // boundary OK
        SnowflakeIdGenerator(maxWorkerId, maxDatacenterId)
    }

    @Test
    fun `ids are strictly increasing and positive`() {
        val gen = SnowflakeIdGenerator(workerId = 1, dataCenterId = 1)
        var prev = gen.nextId()
        assertTrue(prev > 0)
        repeat(20_000) {
            val id = gen.nextId()
            assertTrue(id > 0, "ID should be positive")
            assertTrue(id > prev, "IDs must be strictly increasing: prev=$prev, id=$id")
            prev = id
        }
    }

    @Test
    fun `bit fields are placed correctly`() {
        // Use non-zero worker and dc to make the fields visible
        val workerId: Long = maxWorkerId
        val dcId: Long = maxDatacenterId
        val gen = SnowflakeIdGenerator(workerId, dcId)

        val id = gen.nextId()

        val sequence = id and gen.maxSequence
        val extractedWorker = (id shr workerIdLeftShift) and gen.maxWorkerId
        val extractedDc = (id shr datacenterIdLeftShift) and gen.maxDatacenterId
        val timestampPortion = id shr timestampLeftShift

        assertEquals(workerId, extractedWorker)
        assertEquals(dcId, extractedDc)
        assertTrue(sequence in 0..gen.maxSequence, "Sequence must be within range")
        assertTrue(timestampPortion >= 0, "Timestamp portion must be non-negative")

        // Validate timestamp portion roughly (<= now - epoch)
        val defaultEpoch: Instant = gen.epochStart
        val epochMillis = defaultEpoch.toEpochMilli()
        val nowMillis = System.currentTimeMillis()
        val maxExpectedPortion = nowMillis - epochMillis
        assertTrue(timestampPortion <= maxExpectedPortion, "Timestamp portion should not be in the future")
    }

    @Test
    fun `concurrent generation produces unique ids`() {
        val threads = 8
        val perThread = 5000
        val gen = SnowflakeIdGenerator(workerId = 3, dataCenterId = 2)

        val pool = Executors.newFixedThreadPool(threads)
        val gate = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val set = Collections.newSetFromMap(LongHashMapBackedMutableMap(perThread * threads))

        repeat(threads) {
            pool.submit {
                try {
                    gate.await()
                    repeat(perThread) {
                        val id = gen.nextId()
                        // add returns false if duplicate
                        assertTrue(set.add(id), "Duplicate ID detected: $id")
                    }
                } finally {
                    done.countDown()
                }
            }
        }

        // Start all workers
        gate.countDown()
        // Wait at most 30 seconds
        val finished = done.await(30, TimeUnit.SECONDS)
        pool.shutdown()
        assertTrue(finished, "Workers did not finish in time")

        val expected = threads * perThread
        assertEquals(expected, set.size)
    }
}

/**
 * A very small, specialized mutable map for primitive long keys to boolean that we can wrap as a Set via Collections.newSetFromMap.
 * This avoids boxing overhead in hot loops during the concurrency test and keeps dependencies minimal.
 */
private class LongHashMapBackedMutableMap(initialCapacity: Int) : AbstractMap<Long, Boolean>(), ConcurrentMap<Long, Boolean> {
    private val inner = ConcurrentHashMap<Long, Boolean>(initialCapacity)

    override val entries: MutableSet<MutableMap.MutableEntry<Long, Boolean>>
        get() = inner.entries

    override fun putIfAbsent(key: Long, value: Boolean): Boolean? = inner.putIfAbsent(key, value)
    override fun remove(key: Long, value: Boolean): Boolean = inner.remove(key, value)
    override fun replace(key: Long, oldValue: Boolean, newValue: Boolean): Boolean = inner.replace(key, oldValue, newValue)
    override fun replace(key: Long, value: Boolean): Boolean? = inner.replace(key, value)
    override fun get(key: Long): Boolean? = inner[key]
    override fun put(key: Long, value: Boolean): Boolean? = inner.put(key, value)
    override fun remove(key: Long): Boolean? = inner.remove(key)
}
