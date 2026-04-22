package io.github.dornol.idkit.testing

import io.github.dornol.idkit.flake.ClockMovedBackwardsException
import io.github.dornol.idkit.flake.FlakeIdParser
import io.github.dornol.idkit.ulid.UlidParser
import io.github.dornol.idkit.uuidv7.UuidV7Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TestClockTest {

    @Test
    fun `TestClock starts at DEFAULT_INSTANT by default`() {
        val clock = TestClock()
        assertEquals(TestClock.DEFAULT_INSTANT.toEpochMilli(), clock.now())
        assertEquals(TestClock.DEFAULT_INSTANT, clock.nowInstant())
    }

    @Test
    fun `TestClock advance moves the clock forward via Duration and millis`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        clock.advance(Duration.ofSeconds(5))
        assertEquals(Instant.parse("2024-01-15T00:00:05Z"), clock.nowInstant())
        clock.advance(2_500L)
        assertEquals(Instant.parse("2024-01-15T00:00:07.500Z"), clock.nowInstant())
    }

    @Test
    fun `TestClock regress moves the clock backwards via Duration and millis`() {
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        clock.regress(Duration.ofMinutes(1))
        assertEquals(Instant.parse("2024-01-14T23:59:00Z"), clock.nowInstant())
        clock.regress(30_000L)
        assertEquals(Instant.parse("2024-01-14T23:58:30Z"), clock.nowInstant())
    }

    @Test
    fun `TestClock set overwrites via Instant and epoch millis`() {
        val clock = TestClock()
        val target = Instant.parse("2030-06-15T12:34:56Z")
        clock.set(target)
        assertEquals(target, clock.nowInstant())

        clock.set(1_000_000L)
        assertEquals(Instant.ofEpochMilli(1_000_000L), clock.nowInstant())
    }

    // --- java.time.Clock contract ---------------------------------------------------------------

    @Test
    fun `TestClock is a java_time Clock and implements the full contract`() {
        val clock: Clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        assertEquals(1_705_276_800_000L, clock.millis(), "millis() equals the stored epoch value")
        assertEquals(Instant.parse("2024-01-15T00:00:00Z"), clock.instant())
        assertEquals(ZoneId.of("UTC"), clock.zone)
    }

    @Test
    fun `withZone returns a view that shares the underlying time value`() {
        // The KDoc says withZone shares the underlying AtomicLong; mutating the original must
        // reflect in the view. If withZone copied the counter, this test would fail.
        val utc = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val kst = utc.withZone(ZoneId.of("Asia/Seoul"))
        assertEquals(utc.millis(), kst.millis())
        assertEquals(ZoneId.of("Asia/Seoul"), kst.zone)

        // Mutate the original — the view must see the new time.
        utc.advance(Duration.ofSeconds(10))
        assertEquals(utc.millis(), kst.millis(), "withZone must share the underlying time value")
        assertEquals(Instant.parse("2024-01-15T00:00:10Z"), kst.instant())
    }

    // --- concurrency ---------------------------------------------------------------------------

    @Test
    fun `concurrent advance sums correctly (AtomicLong backing is real)`() {
        // 8 threads × 1000 advance(1) calls each must add exactly 8000 to the clock. If the
        // backing were a plain Long, we'd see lost updates.
        val clock = TestClock(0L)
        val threads = 8
        val perThread = 1_000
        val pool = Executors.newFixedThreadPool(threads)
        val gate = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.submit {
                gate.await()
                repeat(perThread) { clock.advance(1L) }
                done.countDown()
            }
        }
        gate.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish in time")
        pool.shutdown()
        assertEquals((threads * perThread).toLong(), clock.now(), "total advance must equal threads × perThread")
    }

    @Test
    fun `concurrent readers never observe a decrease while a writer advances`() {
        // A reader polling millis() while a writer runs advance(1) in a loop must observe a
        // monotonically non-decreasing sequence. If AtomicLong were missing, we'd see torn
        // reads or regressions.
        val clock = TestClock(0L)
        val writerDone = AtomicBoolean(false)
        val regressionObserved = AtomicBoolean(false)

        val pool = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        pool.submit {
            gate.await()
            repeat(100_000) { clock.advance(1L) }
            writerDone.set(true)
        }
        pool.submit {
            gate.await()
            var last = clock.millis()
            while (!writerDone.get()) {
                val now = clock.millis()
                if (now < last) {
                    regressionObserved.set(true)
                    break
                }
                last = now
            }
        }
        gate.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "workers did not finish in time")
        assertEquals(false, regressionObserved.get(), "reader observed a time regression under concurrent writes")
    }

    // --- factory integration --------------------------------------------------------------------

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
        clock.regress(Duration.ofMinutes(1))
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
    fun `deterministicUlidIdGenerator covers both same-ms and cross-ms paths`() {
        // Within one ms → same-ms +1 increment path.
        // After advance → new-ms path. Both should emit byte-identical output across runs.
        fun run() = deterministicUlidIdGenerator(TestClock(Instant.parse("2024-01-15T00:00:00Z")))
            .let { gen ->
                val a = gen.nextId()  // ms = 0
                val b = gen.nextId()  // ms = 0 same-ms
                // Advance — but we can only advance via the clock we handed in. Rebuild:
                listOf(a, b)
            }

        assertEquals(run(), run())

        // Cross-ms path via explicit advance.
        val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
        val gen = deterministicUlidIdGenerator(clock)
        val u1 = gen.nextId()
        clock.advance(1L)
        val u2 = gen.nextId()
        assertNotEquals(u1.substring(0, 10), u2.substring(0, 10), "new ms must produce a new ts prefix")
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

    @Test
    fun `TestClock is usable as java_time Clock by code that only knows the Clock type`() {
        // The point of extending Clock is compatibility with generic code. Pin that point:
        // a function that consumes a Clock can take our TestClock transparently.
        fun readViaClock(c: Clock): Long = c.millis()

        val tc = TestClock(Instant.parse("2025-12-31T00:00:00Z"))
        assertEquals(tc.now(), readViaClock(tc))
        // The `is Clock` check below is a compile-time reassurance: if TestClock ever stops
        // extending Clock, this expression would not compile (type becomes unreachable).
        @Suppress("USELESS_IS_CHECK")
        assertTrue(tc is Clock, "TestClock must be a java.time.Clock subtype")
    }
}
