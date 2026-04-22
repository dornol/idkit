package io.github.dornol.idkit.flake

import io.github.dornol.idkit.testing.TestClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class FlakeIdParserTest {

    @Test
    fun `decompose round-trips every field from a Snowflake id`() {
        val gen = SnowflakeIdGenerator(workerId = 7, datacenterId = 13)
        val before = System.currentTimeMillis()
        val id = gen.nextId()
        val after = System.currentTimeMillis()

        val parser = FlakeIdParser.of(gen)
        val parts = parser.decompose(id)

        assertEquals(7, parts.workerId)
        assertEquals(13, parts.datacenterId)
        assertEquals(0L, parts.sequence, "first nextId() in a fresh ms slice has sequence 0")
        val ms = parts.timestamp.toEpochMilli()
        assertTrue(ms in before..after, "decoded ts=$ms not in [$before..$after]")
    }

    @Test
    fun `sequence field is actually driven past zero within the same ms`() {
        // A single nextId() per ms always yields sequence=0, so the earlier test passes
        // vacuously. Force the sequence to walk by generating a tight burst and verify
        // the parser reads back 0, 1, 2, ... for ids sharing a timestamp.
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = SnowflakeIdGenerator(workerId = 1, datacenterId = 2, clock = clock)
        val parser = FlakeIdParser.of(gen)

        val ids = (0 until 16).map { gen.nextId() }
        ids.forEachIndexed { i, id ->
            assertEquals(i.toLong(), parser.sequenceOf(id), "sequence at index $i")
        }
    }

    @Test
    fun `per-field accessors agree with decompose`() {
        val gen = SnowflakeIdGenerator(workerId = 5, datacenterId = 9)
        val id = gen.nextId()
        val parser = FlakeIdParser.of(gen)
        val parts = parser.decompose(id)

        assertEquals(parts.timestamp, parser.timestampOf(id))
        assertEquals(parts.datacenterId, parser.datacenterOf(id))
        assertEquals(parts.workerId, parser.workerOf(id))
        assertEquals(parts.sequence, parser.sequenceOf(id))
    }

    @Test
    fun `parser round-trips every field of a custom Flake layout`() {
        // Non-default layout: 40 bits of ts, 3 bits of dc, 12 bits of worker, rest sequence.
        // With a TestClock we can assert the timestamp and sequence fields exactly.
        val epoch = Instant.parse("2024-01-15T00:00:00Z")
        val clock = TestClock(epoch.plusSeconds(60)) // 60 000 ms past epoch
        val gen = FlakeIdGenerator(
            timestampBits = 40,
            datacenterIdBits = 3,
            workerIdBits = 12,
            timestampDivisor = 1L,
            epochStart = epoch,
            datacenterId = 5,
            workerId = 1234,
            clock = clock,
        )
        val parser = FlakeIdParser.of(gen)

        val ids = (0 until 4).map { gen.nextId() }
        ids.forEachIndexed { i, id ->
            val parts = parser.decompose(id)
            assertEquals(5, parts.datacenterId, "decomposed dc at $i")
            assertEquals(1234, parts.workerId, "decomposed worker at $i")
            assertEquals(i.toLong(), parts.sequence, "decomposed sequence at $i")
            assertEquals(
                epoch.plusSeconds(60), parts.timestamp,
                "decomposed ts at $i must equal the clock (pinned)",
            )
        }
    }

    @Test
    fun `standalone-constructed parser decodes every field from ids produced elsewhere`() {
        // Simulates service-A generating ids and service-B parsing them without sharing a
        // generator instance — only the layout spec. Verify the FULL decomposition, not
        // just worker + dc.
        val clock = TestClock(Instant.parse("2024-02-15T12:00:00Z"))
        val gen = SnowflakeIdGenerator(workerId = 1, datacenterId = 2, clock = clock)
        val id1 = gen.nextId()
        val id2 = gen.nextId() // same-ms, sequence 1

        val parserWithLayoutOnly = FlakeIdParser(
            timestampBits = 41,
            datacenterIdBits = 5,
            workerIdBits = 5,
            timestampDivisor = 1L,
            epochStart = Instant.EPOCH,
        )
        val p1 = parserWithLayoutOnly.decompose(id1)
        val p2 = parserWithLayoutOnly.decompose(id2)

        assertEquals(1, p1.workerId)
        assertEquals(2, p1.datacenterId)
        assertEquals(0L, p1.sequence)
        assertEquals(Instant.parse("2024-02-15T12:00:00Z"), p1.timestamp)

        assertEquals(1, p2.workerId)
        assertEquals(2, p2.datacenterId)
        assertEquals(1L, p2.sequence)
        assertEquals(Instant.parse("2024-02-15T12:00:00Z"), p2.timestamp)
    }

    @Test
    fun `timestampDivisor round-trips precisely when encoded and decoded through the parser`() {
        val epoch = Instant.ofEpochMilli(1_000_000L)
        val clock = TestClock(Instant.ofEpochMilli(1_005_000L)) // exactly 5000 ms past epoch
        val gen = FlakeIdGenerator(
            timestampBits = 41,
            datacenterIdBits = 5,
            workerIdBits = 5,
            timestampDivisor = 10L,
            epochStart = epoch,
            datacenterId = 0,
            workerId = 0,
            clock = clock,
        )
        val id = gen.nextId()
        val parser = FlakeIdParser.of(gen)

        // With divisor=10, 5000 ms becomes 500 slices, which re-expands back to 5000 ms.
        val decoded = parser.timestampOf(id).toEpochMilli()
        assertEquals(
            1_005_000L, decoded,
            "decoded ts must match the clock exactly because 5000 is a multiple of divisor=10",
        )
    }

    @Test
    fun `boundary workerId and datacenterId round-trip through the parser`() {
        // Hit the maximum value at each field width.
        val gen = FlakeIdGenerator(
            timestampBits = 41,
            datacenterIdBits = 5,
            workerIdBits = 5,
            timestampDivisor = 1L,
            epochStart = Instant.EPOCH,
            datacenterId = 31,
            workerId = 31,
        )
        val parser = FlakeIdParser.of(gen)
        val parts = parser.decompose(gen.nextId())
        assertEquals(31, parts.workerId)
        assertEquals(31, parts.datacenterId)
    }

    @Test
    fun `constructor validation mirrors the generator`() {
        assertThrows<IllegalArgumentException> { FlakeIdParser(timestampBits = 0) }
        assertThrows<IllegalArgumentException> { FlakeIdParser(timestampBits = -1) }
        assertThrows<IllegalArgumentException> { FlakeIdParser(workerIdBits = 0) }
        assertThrows<IllegalArgumentException> { FlakeIdParser(workerIdBits = 32) }
        assertThrows<IllegalArgumentException> { FlakeIdParser(datacenterIdBits = 0) }
        assertThrows<IllegalArgumentException> { FlakeIdParser(datacenterIdBits = 6) }
        assertThrows<IllegalArgumentException> { FlakeIdParser(timestampDivisor = 0L) }
        assertThrows<IllegalArgumentException> { FlakeIdParser(timestampDivisor = -1L) }
        assertThrows<IllegalArgumentException> {
            // total bits > 63
            FlakeIdParser(timestampBits = 60, datacenterIdBits = 3, workerIdBits = 3)
        }
    }
}
