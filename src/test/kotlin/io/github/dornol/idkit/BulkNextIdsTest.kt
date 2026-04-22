package io.github.dornol.idkit

import io.github.dornol.idkit.flake.FlakeIdGenerator
import io.github.dornol.idkit.flake.SnowflakeIdGenerator
import io.github.dornol.idkit.nanoid.NanoIdGenerator
import io.github.dornol.idkit.ulid.UlidIdGenerator
import io.github.dornol.idkit.ulid.UlidParser
import io.github.dornol.idkit.uuidv7.UuidV7IdGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
    fun `NanoID nextIds produces distinct ids of configured size`() {
        val gen = NanoIdGenerator(size = 12)
        val ids = gen.nextIds(2_000)
        assertEquals(2_000, ids.size)
        assertEquals(2_000, ids.toSet().size)
        for (id in ids) assertEquals(12, id.length)
    }

    @Test
    fun `batch and loop produce equivalent guarantees on a timestamp-seamed Flake`() {
        // With a fixed clock, nextId() called N times and nextIds(N) both must yield monotonic
        // ids and neither should miss a sequence step.
        var fakeNow = Instant.parse("2024-01-15T00:00:00Z").toEpochMilli()
        val gen = object : FlakeIdGenerator(
            timestampBits = 41,
            datacenterIdBits = 5,
            workerIdBits = 5,
            datacenterId = 0,
            workerId = 0,
        ) {
            override fun currentEpochMillis(): Long = fakeNow
        }

        val loopIds = List(100) { gen.nextId() }
        // Advance into the next ms slice so the batch starts a fresh sequence.
        fakeNow += 1
        val batchIds = gen.nextIds(100)

        assertEquals(100, loopIds.size)
        assertEquals(100, batchIds.size)
        for (i in 1 until loopIds.size) assertTrue(loopIds[i] > loopIds[i - 1])
        for (i in 1 until batchIds.size) assertTrue(batchIds[i] > batchIds[i - 1])
        // Batch ids all > loop ids (later timestamp slice).
        assertTrue(batchIds.first() > loopIds.last())
    }
}
