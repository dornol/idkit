package io.github.dornol.idkit.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

/**
 * A mutable [Clock] for deterministic testing.
 *
 * Extends [java.time.Clock] so it can be passed directly to any idkit generator via the
 * `clock` constructor parameter:
 *
 * ```
 * val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
 * val gen = SnowflakeIdGenerator(workerId = 1, datacenterId = 2, clock = clock)
 *
 * val id1 = gen.nextId()
 * clock.advance(Duration.ofSeconds(5))
 * val id2 = gen.nextId()  // timestamp portion is 5000 ms ahead of id1
 * ```
 *
 * Thread-safe: the underlying value lives in an [AtomicLong] so concurrent calls from the
 * tested generator and the test thread do not race.
 *
 * The legacy factory functions in `TestGenerators.kt` (`testSnowflakeIdGenerator`, …) still
 * work — they predate direct `clock` injection and subclass the generator to override
 * `currentEpochMillis()`. New code should prefer passing `TestClock` directly to the
 * generator constructor.
 *
 * @since 2.2.0
 */
class TestClock internal constructor(
    private val current: AtomicLong,
    private val zone: ZoneId,
) : Clock() {

    constructor(
        initial: Long = DEFAULT_INSTANT.toEpochMilli(),
        zone: ZoneId = ZoneId.of("UTC"),
    ) : this(AtomicLong(initial), zone)

    constructor(instant: Instant) : this(instant.toEpochMilli())

    /** Returns the current clock value in epoch milliseconds. */
    fun now(): Long = current.get()

    /** Returns the current clock value as an [Instant]. */
    fun nowInstant(): Instant = Instant.ofEpochMilli(current.get())

    /** Overwrites the clock to [instant]. */
    fun set(instant: Instant) {
        current.set(instant.toEpochMilli())
    }

    /** Overwrites the clock to [epochMillis]. */
    fun set(epochMillis: Long) {
        current.set(epochMillis)
    }

    /** Advances the clock by [duration]. Use [regress] to move backwards for clarity. */
    fun advance(duration: Duration) {
        current.addAndGet(duration.toMillis())
    }

    /** Advances the clock by [millis]. Use [regress] to move backwards for clarity. */
    fun advance(millis: Long) {
        current.addAndGet(millis)
    }

    /** Moves the clock backwards by [duration]. Convenience for `advance(-duration)`. */
    fun regress(duration: Duration) {
        current.addAndGet(-duration.toMillis())
    }

    /** Moves the clock backwards by [millis]. Convenience for `advance(-millis)`. */
    fun regress(millis: Long) {
        current.addAndGet(-millis)
    }

    // --- java.time.Clock contract -------------------------------------------------------------

    override fun millis(): Long = current.get()

    override fun instant(): Instant = Instant.ofEpochMilli(current.get())

    override fun getZone(): ZoneId = zone

    /** Returns a view with the given [zone]; shares the underlying time value. */
    override fun withZone(zone: ZoneId): Clock = TestClock(current, zone)

    companion object {
        /** A neutral default that avoids accidental collisions with real-world timestamps. */
        val DEFAULT_INSTANT: Instant = Instant.parse("2024-01-15T00:00:00Z")
    }
}
