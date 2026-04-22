package io.github.dornol.idkit.flake

import io.github.dornol.idkit.testing.TestClock
import io.github.dornol.idkit.testutil.collectConcurrently
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

class SnowflakeIdGeneratorTest {
    private val workerIdBits = 5
    private val datacenterIdBits = 5
    private val sequenceBits = 12
    private val maxWorkerId = (1 shl workerIdBits) - 1
    private val maxDatacenterId = (1 shl datacenterIdBits) - 1
    private val timestampLeftShift = datacenterIdBits + workerIdBits + sequenceBits
    private val datacenterIdLeftShift = workerIdBits + sequenceBits
    private val workerIdLeftShift = sequenceBits

    @Test
    fun `constructor rejects out of range workerId and datacenterId`() {
        // workerId < 0
        assertThrows<IllegalArgumentException> { SnowflakeIdGenerator(-1, 0) }
        // workerId > MAX
        assertThrows<IllegalArgumentException> { SnowflakeIdGenerator(maxWorkerId + 1, 0) }
        // datacenterId < 0
        assertThrows<IllegalArgumentException> { SnowflakeIdGenerator(0, -1) }
        // datacenterId > MAX
        assertThrows<IllegalArgumentException> { SnowflakeIdGenerator(0, maxDatacenterId + 1) }

        // Boundary must construct AND produce a valid id (not just construct).
        val gen = SnowflakeIdGenerator(maxWorkerId, maxDatacenterId)
        val id = gen.nextId()
        assertTrue(id > 0, "boundary-worker/dc generator must still emit positive ids")
        assertEquals(
            maxWorkerId, (id shr workerIdLeftShift).toInt() and gen.maxWorkerId,
            "boundary workerId must round-trip through the bit layout",
        )
        assertEquals(
            maxDatacenterId, (id shr datacenterIdLeftShift).toInt() and gen.maxDatacenterId,
            "boundary datacenterId must round-trip through the bit layout",
        )
    }

    @Test
    fun `pins the Twitter Snowflake layout 41-5-5-12`() {
        val gen = SnowflakeIdGenerator(workerId = 1, datacenterId = 2)
        assertEquals(41, gen.timestampBits)
        assertEquals(5, gen.datacenterIdBits)
        assertEquals(5, gen.workerIdBits)
        assertEquals(12, gen.sequenceBits)
        assertEquals(4095L, gen.maxSequence)
        assertEquals(1L, gen.timestampDivisor)
    }

    @Test
    fun `ids are strictly increasing and positive`() {
        val gen = SnowflakeIdGenerator(workerId = 1, datacenterId = 1)
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
    fun `bit fields are placed correctly and sequence actually varies`() {
        val workerId: Int = maxWorkerId
        val dcId: Int = maxDatacenterId
        val gen = SnowflakeIdGenerator(workerId, dcId)

        // Fire a burst so the sequence counter spans multiple values (>1 id/ms expected on
        // any modern machine).
        val ids = (0 until 64).map { gen.nextId() }

        val workers = ids.map { (it shr workerIdLeftShift).toInt() and gen.maxWorkerId }
        val dcs = ids.map { (it shr datacenterIdLeftShift).toInt() and gen.maxDatacenterId }
        val sequences = ids.map { it and gen.maxSequence }

        assertTrue(workers.all { it == workerId }, "all ids must encode the configured workerId")
        assertTrue(dcs.all { it == dcId }, "all ids must encode the configured datacenterId")
        assertTrue(sequences.all { it in 0..gen.maxSequence }, "sequence must stay in range")
        assertTrue(
            sequences.toSet().size >= 2,
            "sequence field must actually vary across 64 ids: got $sequences",
        )
    }

    @Test
    fun `custom epoch produces an exact timestamp delta under a fixed clock`() {
        // With a TestClock we can assert the exact timestamp portion rather than ±1s fudge.
        val epoch = Instant.parse("2020-01-01T00:00:00Z")
        val clock = TestClock(epoch.plus(Duration.ofSeconds(10))) // 10 000 ms after epoch
        val gen = SnowflakeIdGenerator(
            workerId = 1,
            datacenterId = 1,
            epochStart = epoch,
            clock = clock,
        )
        val id = gen.nextId()
        val timestampPortion = id shr timestampLeftShift
        assertEquals(10_000L, timestampPortion, "timestamp portion must equal exactly (now - epoch) in ms")

        // Default-epoch (1970-01-01) generator produces a much larger timestamp portion.
        val defaultGen = SnowflakeIdGenerator(workerId = 1, datacenterId = 1)
        val defaultTsPortion = defaultGen.nextId() shr timestampLeftShift
        assertTrue(
            defaultTsPortion > timestampPortion,
            "default-epoch ts ($defaultTsPortion) must exceed custom-epoch ts ($timestampPortion) by billions",
        )
    }

    @Test
    fun `throws ClockMovedBackwardsException when the clock regresses`() {
        // The regression contract is inherited from FlakeIdGenerator, but Snowflake is the
        // public surface most users actually instantiate — so pin the behaviour here too.
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = SnowflakeIdGenerator(workerId = 1, datacenterId = 2, clock = clock)
        val primed = gen.nextId()
        clock.regress(Duration.ofSeconds(5))
        val ex = assertThrows<ClockMovedBackwardsException> { gen.nextId() }
        assertEquals(5_000L, ex.driftAmount)
        assertEquals(1L, ex.timestampDivisor)

        // After recovery the generator must produce strictly greater ids.
        clock.advance(Duration.ofSeconds(10))
        val recovered = gen.nextId()
        assertTrue(recovered > primed)
    }

    @Test
    fun `concurrent generation produces unique ids with correct worker and datacenter bits`() {
        val threads = 8
        val perThread = 5000
        val gen = SnowflakeIdGenerator(workerId = 3, datacenterId = 2)

        val ids = collectConcurrently(threads, perThread) { gen.nextId() }
        assertEquals(threads * perThread, ids.size)
        assertTrue(ids.all { it > 0L }, "all concurrent ids must be positive")
        assertTrue(
            ids.all { ((it shr workerIdLeftShift).toInt() and gen.maxWorkerId) == 3 },
            "workerId must decode to 3 for every concurrent id",
        )
        assertTrue(
            ids.all { ((it shr datacenterIdLeftShift).toInt() and gen.maxDatacenterId) == 2 },
            "datacenterId must decode to 2 for every concurrent id",
        )
    }
}
