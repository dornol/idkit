package io.github.dornol.idkit

import io.github.dornol.idkit.flake.FlakeIdParser
import io.github.dornol.idkit.flake.SnowflakeIdGenerator
import io.github.dornol.idkit.testing.TestClock
import io.github.dornol.idkit.ulid.UlidIdGenerator
import io.github.dornol.idkit.ulid.UlidParser
import io.github.dornol.idkit.uuidv7.UuidV7IdGenerator
import io.github.dornol.idkit.uuidv7.UuidV7Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Verifies the 2.3.0 `clock: Clock` constructor parameter. The generators should observe
 * time through the injected [Clock] rather than `System.currentTimeMillis()`, without any
 * subclass / `currentEpochMillis()` override dance.
 */
class ClockInjectionTest {

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

    @Test
    fun `any java_time Clock works, not just TestClock`() {
        // Pin the JDK's Clock.fixed to a specific instant. Confirms that the generator's
        // contract is against the standard java.time.Clock abstraction, not a TestClock
        // leak.
        val fixed: Clock = Clock.fixed(
            Instant.parse("2030-01-01T00:00:00Z"),
            ZoneOffset.UTC,
        )
        val gen = SnowflakeIdGenerator(workerId = 0, datacenterId = 0, clock = fixed)
        val parser = FlakeIdParser.of(gen)

        val id1 = gen.nextId()
        // Same instant on every nextId() call — id2's timestamp field should match id1's.
        val id2 = gen.nextId()
        assertEquals(parser.timestampOf(id1), parser.timestampOf(id2))
    }

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
        // Same epoch instant, different reported zone.
        assertEquals(utc.millis(), kst.millis())
        assertEquals(ZoneId.of("Asia/Seoul"), kst.zone)
    }
}
