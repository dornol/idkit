package io.github.dornol.idkit.flake

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
        assertTrue(parts.sequence in 0..4095, "sequence out of range: ${parts.sequence}")
        val ms = parts.timestamp.toEpochMilli()
        assertTrue(ms in before..after, "decoded ts=$ms not in [$before..$after]")
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
    fun `parser round-trips a custom Flake layout`() {
        // Non-default layout: 40 bits of ts, 3 bits of dc, 12 bits of worker, rest sequence.
        val customEpoch = Instant.ofEpochMilli(System.currentTimeMillis() - 60_000L)
        val gen = FlakeIdGenerator(
            timestampBits = 40,
            datacenterIdBits = 3,
            workerIdBits = 12,
            timestampDivisor = 1L,
            epochStart = customEpoch,
            datacenterId = 5,
            workerId = 1234,
        )
        val id = gen.nextId()

        val parser = FlakeIdParser.of(gen)
        val parts = parser.decompose(id)

        assertEquals(5, parts.datacenterId)
        assertEquals(1234, parts.workerId)
        assertTrue(parts.timestamp.toEpochMilli() >= customEpoch.toEpochMilli())
    }

    @Test
    fun `parser works across instances with matching layout`() {
        // Simulates service-A generating ids and service-B parsing them without sharing a
        // generator instance — only the layout spec.
        val gen = SnowflakeIdGenerator(workerId = 1, datacenterId = 2)
        val id = gen.nextId()

        val parserWithLayoutOnly = FlakeIdParser(
            timestampBits = 41,
            datacenterIdBits = 5,
            workerIdBits = 5,
            timestampDivisor = 1L,
            epochStart = Instant.EPOCH,
        )
        val parts = parserWithLayoutOnly.decompose(id)
        assertEquals(1, parts.workerId)
        assertEquals(2, parts.datacenterId)
    }

    @Test
    fun `timestampDivisor is honored when decoding`() {
        val epoch = Instant.ofEpochMilli(1_000_000L)
        val gen = FlakeIdGenerator(
            timestampBits = 41,
            datacenterIdBits = 5,
            workerIdBits = 5,
            timestampDivisor = 10L,
            epochStart = epoch,
            datacenterId = 0,
            workerId = 0,
        )
        val id = gen.nextId()
        val parser = FlakeIdParser.of(gen)

        val ts = parser.timestampOf(id).toEpochMilli()
        val now = System.currentTimeMillis()
        // Divisor=10 → resolution is 10 ms, so the decoded ts can trail now by up to 10 ms.
        assertTrue(ts in (now - 1_000)..now, "decoded ts=$ts not close to now=$now")
    }

    @Test
    fun `constructor validation mirrors the generator`() {
        assertThrows<IllegalArgumentException> {
            FlakeIdParser(timestampBits = 0)
        }
        assertThrows<IllegalArgumentException> {
            FlakeIdParser(workerIdBits = 32)
        }
        assertThrows<IllegalArgumentException> {
            FlakeIdParser(datacenterIdBits = 6)
        }
        assertThrows<IllegalArgumentException> {
            FlakeIdParser(timestampDivisor = 0L)
        }
        assertThrows<IllegalArgumentException> {
            // total bits > 63
            FlakeIdParser(timestampBits = 60, datacenterIdBits = 3, workerIdBits = 3)
        }
    }
}
