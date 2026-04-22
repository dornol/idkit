package io.github.dornol.idkit

import io.github.dornol.idkit.flake.ClockMovedBackwardsException
import io.github.dornol.idkit.flake.FlakeIdGenerator
import io.github.dornol.idkit.flake.FlakeIdParser
import io.github.dornol.idkit.flake.SnowflakeIdGenerator
import io.github.dornol.idkit.nanoid.NanoIdGenerator
import io.github.dornol.idkit.testing.TestClock
import io.github.dornol.idkit.ulid.UlidIdGenerator
import io.github.dornol.idkit.ulid.UlidParser
import io.github.dornol.idkit.uuidv7.UuidV7IdGenerator
import io.github.dornol.idkit.uuidv7.UuidV7Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class BulkNextIdsTest {

    @Test
    fun `nextIds returns empty list for count zero on every generator`() {
        val generators: List<IdGenerator<*>> = listOf(
            SnowflakeIdGenerator(workerId = 1, datacenterId = 1),
            FlakeIdGenerator(datacenterId = 1, workerId = 1),
            UuidV7IdGenerator(),
            UlidIdGenerator(),
            NanoIdGenerator(),
        )
        for (gen in generators) {
            assertEquals(emptyList<Any>(), gen.nextIds(0))
        }
    }

    @Test
    fun `nextIds rejects negative counts`() {
        val generators: List<IdGenerator<*>> = listOf(
            SnowflakeIdGenerator(workerId = 1, datacenterId = 1),
            FlakeIdGenerator(datacenterId = 1, workerId = 1),
            UuidV7IdGenerator(),
            UlidIdGenerator(),
            NanoIdGenerator(),
        )
        for (gen in generators) {
            assertThrows(IllegalArgumentException::class.java) { gen.nextIds(-1) }
        }
    }

    @Test
    fun `Snowflake nextIds produces strictly increasing ids in emission order`() {
        val gen = SnowflakeIdGenerator(workerId = 3, datacenterId = 5)
        val ids = gen.nextIds(10_000)
        assertEquals(10_000, ids.size)
        for (i in 1 until ids.size) {
            assertTrue(ids[i] > ids[i - 1]) { "ids[$i]=${ids[i]} <= ids[${i - 1}]=${ids[i - 1]}" }
        }
    }

    @Test
    fun `Snowflake nextIds under fixed clock produces contiguous sequence values`() {
        // The critical semantic claim: nextIds(N) must produce the SAME sequence that N
        // synchronous nextId() calls would produce — pinned timestamp, sequence 0..N-1.
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = SnowflakeIdGenerator(workerId = 3, datacenterId = 5, clock = clock)
        val parser = FlakeIdParser.of(gen)

        val batch = gen.nextIds(100)
        batch.forEachIndexed { i, id ->
            assertEquals(3, parser.workerOf(id))
            assertEquals(5, parser.datacenterOf(id))
            assertEquals(i.toLong(), parser.sequenceOf(id), "sequence at $i must equal $i")
            assertEquals(
                Instant.parse("2024-01-15T00:00:00Z"), parser.timestampOf(id),
                "timestamp at $i must stay pinned across the batch",
            )
        }
    }

    @Test
    fun `Flake nextIds produces strictly increasing ids in emission order`() {
        val gen = FlakeIdGenerator(
            timestampBits = 41,
            datacenterIdBits = 5,
            workerIdBits = 5,
            datacenterId = 2,
            workerId = 7,
        )
        val ids = gen.nextIds(5_000)
        assertEquals(5_000, ids.size)
        for (i in 1 until ids.size) {
            assertTrue(ids[i] > ids[i - 1])
        }
    }

    @Test
    fun `UUID v7 nextIds produces distinct, strictly increasing uuids`() {
        val gen = UuidV7IdGenerator()
        val uuids = gen.nextIds(5_000)
        assertEquals(5_000, uuids.size)
        assertEquals(5_000, uuids.toSet().size)
        for (i in 1 until uuids.size) {
            assertTrue(uuids[i] > uuids[i - 1])
            assertEquals(7, uuids[i].version(), "every batched UUID must be v7")
        }
    }

    @Test
    fun `UUID v7 nextIds under fixed clock walks the counter 0 to N-1`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = UuidV7IdGenerator(clock = clock)
        val batch = gen.nextIds(512)
        batch.forEachIndexed { i, u ->
            // counter lives in msb bits 52..63 (the low 12 bits of mostSigBits).
            val counter = u.mostSignificantBits and 0xFFFL
            assertEquals(i.toLong(), counter, "counter at $i must equal $i under fixed clock")
            assertEquals(Instant.parse("2024-01-15T00:00:00Z"), UuidV7Parser.timestampOf(u))
        }
    }

    @Test
    fun `ULID nextIds produces strictly lexicographically increasing values`() {
        val gen = UlidIdGenerator()
        val ulids = gen.nextIds(5_000)
        assertEquals(5_000, ulids.size)
        for (i in 1 until ulids.size) {
            assertTrue(ulids[i] > ulids[i - 1])
            assertTrue(UlidParser.isValid(ulids[i]))
        }
    }

    @Test
    fun `NanoID nextIds produces distinct alphabet-valid ids of configured size`() {
        val gen = NanoIdGenerator(size = 12)
        val alphabet = NanoIdGenerator.DEFAULT_ALPHABET.toSet()
        val ids = gen.nextIds(2_000)
        assertEquals(2_000, ids.size)
        assertEquals(2_000, ids.toSet().size)
        for (id in ids) {
            assertEquals(12, id.length)
            id.forEach { c -> assertTrue(c in alphabet, "illegal char '$c' in '$id'") }
        }
    }

    @Test
    fun `batch and loop produce the same sequence under a fixed clock (semantic equivalence)`() {
        // Two generators of equal config + same initial clock MUST emit the same sequence of
        // ids whether we call nextId() N times or nextIds(N) once. This pins the KDoc claim
        // "Equivalent to calling nextId() count times".
        val clockA = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val clockB = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val genA = SnowflakeIdGenerator(workerId = 1, datacenterId = 2, clock = clockA)
        val genB = SnowflakeIdGenerator(workerId = 1, datacenterId = 2, clock = clockB)

        val loopIds = List(200) { genA.nextId() }
        val batchIds = genB.nextIds(200)

        assertEquals(loopIds, batchIds, "loop and batch must yield the identical id sequence")
    }

    @Test
    fun `nextIds propagates mid-batch ClockMovedBackwardsException and stays consistent afterwards`() {
        // KDoc claim: "A mid-batch ClockMovedBackwardsException propagates and already-generated
        // ids in this call are discarded; the generator stays consistent for future calls once
        // the clock recovers."
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = SnowflakeIdGenerator(workerId = 1, datacenterId = 2, clock = clock)

        // Prime once at t0, then regress → the next batch must throw.
        val primed = gen.nextId()
        clock.regress(Duration.ofSeconds(5))
        assertThrows(ClockMovedBackwardsException::class.java) { gen.nextIds(10) }

        // Recover: advance past the original timestamp and run a batch. Must succeed and
        // every id must be strictly greater than the primed id.
        clock.advance(Duration.ofSeconds(10))
        val recovered = gen.nextIds(5)
        assertEquals(5, recovered.size)
        recovered.forEach { assertTrue(it > primed, "recovered id $it must be > primed $primed") }
        for (i in 1 until recovered.size) assertTrue(recovered[i] > recovered[i - 1])
    }
}
