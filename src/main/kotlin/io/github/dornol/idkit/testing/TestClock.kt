package io.github.dornol.idkit.testing

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * A mutable clock for deterministic testing against generators that expose the
 * `currentEpochMillis()` seam ([io.github.dornol.idkit.flake.FlakeIdGenerator],
 * [io.github.dornol.idkit.ulid.UlidIdGenerator], [io.github.dornol.idkit.uuidv7.UuidV7IdGenerator]).
 *
 * Thread-safe: the underlying value lives in an [AtomicLong] so concurrent calls from the
 * tested generator and the test thread do not race.
 *
 * Usage:
 * ```
 * val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
 * val gen = testSnowflakeIdGenerator(clock, workerId = 1, datacenterId = 2)
 *
 * val id1 = gen.nextId()
 * clock.advance(Duration.ofSeconds(5))
 * val id2 = gen.nextId()
 * // id2's timestamp field is 5000 ms ahead of id1's
 * ```
 *
 * @since 2.2.0
 */
class TestClock(initial: Long) {

    constructor() : this(DEFAULT_INSTANT.toEpochMilli())
    constructor(instant: Instant) : this(instant.toEpochMilli())

    private val current = AtomicLong(initial)

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

    companion object {
        /** A neutral default that avoids accidental collisions with real-world timestamps. */
        val DEFAULT_INSTANT: Instant = Instant.parse("2024-01-15T00:00:00Z")
    }
}
