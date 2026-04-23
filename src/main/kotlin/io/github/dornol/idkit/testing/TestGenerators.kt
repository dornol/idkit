package io.github.dornol.idkit.testing

import io.github.dornol.idkit.IdGeneratorListener
import io.github.dornol.idkit.flake.FlakeIdGenerator
import io.github.dornol.idkit.flake.SnowflakeIdGenerator
import io.github.dornol.idkit.ulid.UlidIdGenerator
import io.github.dornol.idkit.uuidv7.UuidV7IdGenerator
import java.time.Duration
import java.time.Instant

/**
 * Factory helpers that wire a [TestClock] into each generator family so tests can advance
 * time deterministically. Since 2.3.0 these just pass the [TestClock] as the `clock`
 * constructor parameter — for clock-only scenarios you can skip the factory entirely:
 *
 * ```
 * val gen = SnowflakeIdGenerator(workerId = 1, datacenterId = 2, clock = TestClock())
 * ```
 *
 * The factories remain for parameter-default convenience and for scenarios that layer
 * *additional* test hooks on top of the clock — for instance [deterministicUlidIdGenerator]
 * subclasses the generator to override `drawRandomness()` (the clock is still passed via
 * the constructor; only the randomness seam needs a subclass).
 *
 * @since 2.2.0
 */

/** Snowflake generator whose clock is driven by [clock]. */
fun testSnowflakeIdGenerator(
    clock: TestClock,
    workerId: Int = 0,
    datacenterId: Int = 0,
    epochStart: Instant = Instant.EPOCH,
    clockRegressionTolerance: Duration = FlakeIdGenerator.DEFAULT_CLOCK_REGRESSION_TOLERANCE,
    listener: IdGeneratorListener = IdGeneratorListener.NOOP,
): SnowflakeIdGenerator = SnowflakeIdGenerator(
    workerId = workerId,
    datacenterId = datacenterId,
    epochStart = epochStart,
    clockRegressionTolerance = clockRegressionTolerance,
    clock = clock,
    listener = listener,
)

/** Fully-configurable Flake generator whose clock is driven by [clock]. */
fun testFlakeIdGenerator(
    clock: TestClock,
    timestampBits: Int = 41,
    datacenterIdBits: Int = 5,
    workerIdBits: Int = 5,
    timestampDivisor: Long = 1L,
    epochStart: Instant = Instant.EPOCH,
    datacenterId: Int = 0,
    workerId: Int = 0,
    clockRegressionTolerance: Duration = FlakeIdGenerator.DEFAULT_CLOCK_REGRESSION_TOLERANCE,
    listener: IdGeneratorListener = IdGeneratorListener.NOOP,
): FlakeIdGenerator = FlakeIdGenerator(
    timestampBits = timestampBits,
    datacenterIdBits = datacenterIdBits,
    workerIdBits = workerIdBits,
    timestampDivisor = timestampDivisor,
    epochStart = epochStart,
    datacenterId = datacenterId,
    workerId = workerId,
    clockRegressionTolerance = clockRegressionTolerance,
    clock = clock,
    listener = listener,
)

/** ULID generator whose clock is driven by [clock]. Randomness is still non-deterministic. */
fun testUlidIdGenerator(clock: TestClock = TestClock()): UlidIdGenerator =
    UlidIdGenerator(clock = clock)

/**
 * ULID generator that is **byte-identically reproducible** across test runs:
 *  - the clock is driven by [clock]
 *  - the initial randomness seeded on each new ms slice is all zeros
 *
 * Because same-ms ULIDs simply increment the 80-bit randomness by 1, the emitted strings
 * form a stable sequence (e.g. `01HV...0000000000000000`, `01HV...0000000000000001`, …).
 * Ideal for snapshot tests.
 */
fun deterministicUlidIdGenerator(clock: TestClock = TestClock()): UlidIdGenerator =
    object : UlidIdGenerator(clock = clock) {
        override fun drawRandomness(): LongArray = longArrayOf(0L, 0L)
    }

/** UUID v7 generator whose clock is driven by [clock]. */
fun testUuidV7IdGenerator(clock: TestClock = TestClock()): UuidV7IdGenerator =
    UuidV7IdGenerator(clock = clock)
