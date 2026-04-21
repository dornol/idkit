package io.github.dornol.idkit.testing

import io.github.dornol.idkit.flake.ClockMovedBackwardsException
import io.github.dornol.idkit.flake.FlakeIdParser
import io.github.dornol.idkit.ulid.UlidParser
import io.github.dornol.idkit.uuidv7.UuidV7Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

class TestClockTest {

    @Test
    fun `TestClock starts at DEFAULT_INSTANT by default`() {
        val clock = TestClock()
        assertEquals(TestClock.DEFAULT_INSTANT.toEpochMilli(), clock.now())
        assertEquals(TestClock.DEFAULT_INSTANT, clock.nowInstant())
    }

    @Test
    fun `TestClock advance moves the clock forward`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        clock.advance(Duration.ofSeconds(5))
        assertEquals(Instant.parse("2024-01-15T00:00:05Z"), clock.nowInstant())
    }

    @Test
    fun `TestClock advance with negative value simulates regression`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        clock.advance(-60_000L)
        assertEquals(Instant.parse("2024-01-14T23:59:00Z"), clock.nowInstant())
    }

    @Test
    fun `TestClock set overwrites the current time`() {
        val clock = TestClock()
        val target = Instant.parse("2030-06-15T12:34:56Z")
        clock.set(target)
        assertEquals(target, clock.nowInstant())
    }

    @Test
    fun `testSnowflakeIdGenerator drives timestamps from the TestClock`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = testSnowflakeIdGenerator(clock, workerId = 1, datacenterId = 2)
        val parser = FlakeIdParser.of(gen)

        val id1 = gen.nextId()
        clock.advance(Duration.ofSeconds(5))
        val id2 = gen.nextId()

        assertEquals(Instant.parse("2024-01-15T00:00:00Z"), parser.timestampOf(id1))
        assertEquals(Instant.parse("2024-01-15T00:00:05Z"), parser.timestampOf(id2))
        assertEquals(1, parser.workerOf(id2))
        assertEquals(2, parser.datacenterOf(id2))
    }

    @Test
    fun `testSnowflakeIdGenerator throws on simulated clock regression`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = testSnowflakeIdGenerator(clock, workerId = 1, datacenterId = 2)

        gen.nextId()
        clock.advance(-60_000L) // back 1 minute
        assertThrows<ClockMovedBackwardsException> { gen.nextId() }
    }

    @Test
    fun `testUlidIdGenerator drives timestamps from the TestClock`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = testUlidIdGenerator(clock)

        val u1 = gen.nextId()
        clock.advance(Duration.ofSeconds(1))
        val u2 = gen.nextId()

        assertEquals(Instant.parse("2024-01-15T00:00:00Z"), UlidParser.timestampOf(u1))
        assertEquals(Instant.parse("2024-01-15T00:00:01Z"), UlidParser.timestampOf(u2))
    }

    @Test
    fun `deterministicUlidIdGenerator produces byte-identical output across runs`() {
        // Two independent generator instances with the same clock & same zero randomness
        // must emit the same sequence of ULIDs.
        val run1 = deterministicUlidIdGenerator(TestClock(Instant.parse("2024-01-15T00:00:00Z")))
            .let { gen -> List(5) { gen.nextId() } }
        val run2 = deterministicUlidIdGenerator(TestClock(Instant.parse("2024-01-15T00:00:00Z")))
            .let { gen -> List(5) { gen.nextId() } }

        assertEquals(run1, run2)
        // Successive ULIDs in the same ms must strictly increase (counter path).
        for (i in 1 until run1.size) assertTrue(run1[i] > run1[i - 1])
    }

    @Test
    fun `testUuidV7IdGenerator drives timestamps from the TestClock`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = testUuidV7IdGenerator(clock)

        val uuid1 = gen.nextId()
        clock.advance(Duration.ofSeconds(10))
        val uuid2 = gen.nextId()

        assertEquals(Instant.parse("2024-01-15T00:00:00Z"), UuidV7Parser.timestampOf(uuid1))
        assertEquals(Instant.parse("2024-01-15T00:00:10Z"), UuidV7Parser.timestampOf(uuid2))
        assertNotEquals(uuid1, uuid2)
    }

    @Test
    fun `testFlakeIdGenerator allows advancing the clock to span timestamp slices`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = testFlakeIdGenerator(
            clock,
            timestampBits = 41,
            datacenterIdBits = 5,
            workerIdBits = 5,
            timestampDivisor = 10L,           // 10 ms resolution
            epochStart = Instant.EPOCH,
            datacenterId = 3,
            workerId = 7,
        )
        val parser = FlakeIdParser.of(gen)

        val id1 = gen.nextId()
        clock.advance(100L) // 10 slices forward
        val id2 = gen.nextId()

        assertTrue(id2 > id1)
        assertEquals(3, parser.datacenterOf(id2))
        assertEquals(7, parser.workerOf(id2))
    }
}
