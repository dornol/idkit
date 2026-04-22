package io.github.dornol.idkit

import io.github.dornol.idkit.flake.FlakeIdGenerator
import io.github.dornol.idkit.flake.FlakeIdParser
import io.github.dornol.idkit.flake.SnowflakeIdGenerator
import io.github.dornol.idkit.testing.TestClock
import io.github.dornol.idkit.ulid.UlidIdGenerator
import io.github.dornol.idkit.ulid.UlidParser
import io.github.dornol.idkit.uuidv7.UuidV7IdGenerator
import io.github.dornol.idkit.uuidv7.UuidV7Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Verifies the 2.3.0 `clock: Clock` constructor parameter across every time-ordered generator.
 */
class ClockInjectionTest {

    // --- basic injection -----------------------------------------------------------------------

    @Test
    fun `Snowflake reads time from the injected Clock`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = SnowflakeIdGenerator(workerId = 1, datacenterId = 2, clock = clock)
        val parser = FlakeIdParser.of(gen)

        val id1 = gen.nextId()
        clock.advance(Duration.ofSeconds(10))
        val id2 = gen.nextId()

        assertEquals(Instant.parse("2024-01-15T00:00:00Z"), parser.timestampOf(id1))
        assertEquals(Instant.parse("2024-01-15T00:00:10Z"), parser.timestampOf(id2))
    }

    @Test
    fun `ULID reads time from the injected Clock`() {
        val clock = TestClock(Instant.parse("2024-06-01T12:00:00Z"))
        val gen = UlidIdGenerator(clock = clock)

        val u1 = gen.nextId()
        clock.advance(Duration.ofSeconds(3))
        val u2 = gen.nextId()

        assertEquals(Instant.parse("2024-06-01T12:00:00Z"), UlidParser.timestampOf(u1))
        assertEquals(Instant.parse("2024-06-01T12:00:03Z"), UlidParser.timestampOf(u2))
    }

    @Test
    fun `UUID v7 reads time from the injected Clock`() {
        val clock = TestClock(Instant.parse("2025-03-20T08:00:00Z"))
        val gen = UuidV7IdGenerator(clock = clock)

        val u1 = gen.nextId()
        clock.advance(Duration.ofSeconds(5))
        val u2 = gen.nextId()

        assertEquals(Instant.parse("2025-03-20T08:00:00Z"), UuidV7Parser.timestampOf(u1))
        assertEquals(Instant.parse("2025-03-20T08:00:05Z"), UuidV7Parser.timestampOf(u2))
    }

    // --- works against any java.time.Clock, not only TestClock --------------------------------

    @Test
    fun `Snowflake works with Clock_fixed - pinned timestamp, distinct sequence-bearing ids`() {
        val fixed: Clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC)
        val gen = SnowflakeIdGenerator(workerId = 0, datacenterId = 0, clock = fixed)
        val parser = FlakeIdParser.of(gen)

        val id1 = gen.nextId()
        val id2 = gen.nextId()
        // Timestamps pinned; ids differ via the sequence field.
        assertEquals(parser.timestampOf(id1), parser.timestampOf(id2))
        assertNotEquals(id1, id2, "fixed-clock ids must differ via the sequence counter")
        assertEquals(0L, parser.sequenceOf(id1))
        assertEquals(1L, parser.sequenceOf(id2))
    }

    @Test
    fun `UUID v7 works with Clock_fixed - pinned timestamp, +1 counter steps`() {
        val fixed: Clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC)
        val gen = UuidV7IdGenerator(clock = fixed)
        val u1 = gen.nextId()
        val u2 = gen.nextId()
        assertEquals(UuidV7Parser.timestampOf(u1), UuidV7Parser.timestampOf(u2))
        assertNotEquals(u1, u2, "fixed-clock UUIDs must differ via the counter")
        // Counter advances by exactly +1 in the low 12 bits of the most-significant Long.
        val delta = (u2.mostSignificantBits and 0xFFFL) - (u1.mostSignificantBits and 0xFFFL)
        assertEquals(1L, delta, "counter must advance by +1 under a fixed clock")
    }

    @Test
    fun `ULID works with Clock_fixed - identical timestamp prefix, distinct strings`() {
        val fixed: Clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC)
        val gen = UlidIdGenerator(clock = fixed)
        val u1 = gen.nextId()
        val u2 = gen.nextId()
        assertEquals(u1.substring(0, 10), u2.substring(0, 10), "timestamp prefix must be pinned")
        assertNotEquals(u1, u2, "fixed-clock ULIDs must differ via the +1 randomness increment")
        assertTrue(u2 > u1, "ULIDs must stay lexicographically monotonic")
    }

    // --- backward compatibility: currentEpochMillis override wins over clock -------------------

    @Test
    fun `subclass currentEpochMillis override takes precedence over injected Clock on Snowflake`() {
        // KDoc claim on FlakeIdGenerator.currentEpochMillis: the test seam is preserved for
        // backward compatibility. A subclass that overrides currentEpochMillis must see its
        // override win, even if a (wrong) Clock was also passed in.
        val wrongClock = TestClock(Instant.parse("2030-01-01T00:00:00Z"))
        val forcedMs = Instant.parse("2024-01-15T00:00:00Z").toEpochMilli()
        val gen = object : SnowflakeIdGenerator(workerId = 0, datacenterId = 0, clock = wrongClock) {
            override fun currentEpochMillis(): Long = forcedMs
        }
        val parser = FlakeIdParser.of(gen)
        assertEquals(Instant.ofEpochMilli(forcedMs), parser.timestampOf(gen.nextId()))
    }

    @Test
    fun `subclass currentEpochMillis override takes precedence over injected Clock on UUID v7`() {
        val wrongClock = TestClock(Instant.parse("2030-01-01T00:00:00Z"))
        val forcedMs = Instant.parse("2024-01-15T00:00:00Z").toEpochMilli()
        val gen = object : UuidV7IdGenerator(clock = wrongClock) {
            override fun currentEpochMillis(): Long = forcedMs
        }
        assertEquals(Instant.ofEpochMilli(forcedMs), UuidV7Parser.timestampOf(gen.nextId()))
    }

    @Test
    fun `subclass currentEpochMillis override takes precedence over injected Clock on ULID`() {
        val wrongClock = TestClock(Instant.parse("2030-01-01T00:00:00Z"))
        val forcedMs = Instant.parse("2024-01-15T00:00:00Z").toEpochMilli()
        val gen = object : UlidIdGenerator(clock = wrongClock) {
            override fun currentEpochMillis(): Long = forcedMs
        }
        assertEquals(Instant.ofEpochMilli(forcedMs), UlidParser.timestampOf(gen.nextId()))
    }

    @Test
    fun `subclass currentEpochMillis override takes precedence over injected Clock on Flake`() {
        val wrongClock = TestClock(Instant.parse("2030-01-01T00:00:00Z"))
        val forcedMs = Instant.parse("2024-01-15T00:00:00Z").toEpochMilli()
        val gen = object : FlakeIdGenerator(
            timestampBits = 41,
            datacenterIdBits = 5,
            workerIdBits = 5,
            datacenterId = 0,
            workerId = 0,
            clock = wrongClock,
        ) {
            override fun currentEpochMillis(): Long = forcedMs
        }
        val parser = FlakeIdParser.of(gen)
        assertEquals(Instant.ofEpochMilli(forcedMs), parser.timestampOf(gen.nextId()))
    }

    // --- TestClock Clock-contract smoke ---------------------------------------------------------

    @Test
    fun `TestClock is a java_time Clock subtype`() {
        val clock: Clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        assertEquals(Instant.parse("2024-01-15T00:00:00Z"), clock.instant())
        assertEquals(ZoneId.of("UTC"), clock.zone)
    }

    @Test
    fun `TestClock withZone shares the underlying time value`() {
        val utc = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val kst = utc.withZone(ZoneId.of("Asia/Seoul"))
        assertEquals(utc.millis(), kst.millis())
        assertEquals(ZoneId.of("Asia/Seoul"), kst.zone)
    }
}
