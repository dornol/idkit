package io.github.dornol.idkit.testing

import io.github.dornol.idkit.flake.FlakeIdGenerator
import io.github.dornol.idkit.flake.SnowflakeIdGenerator
import io.github.dornol.idkit.ulid.UlidIdGenerator
import io.github.dornol.idkit.uuidv7.UuidV7IdGenerator
import java.time.Instant

/**
 * Factory helpers that wire the `currentEpochMillis()` seam of each generator family to a
 * shared [TestClock], so tests can advance time deterministically instead of relying on the
 * wall clock.
 *
 * These helpers are intentionally thin — they only install the clock seam. If you need more
 * than a deterministic clock (e.g., deterministic randomness on top), use
 * [deterministicUlidIdGenerator].
 *
 * @since 2.2.0
 */

/** Snowflake generator whose clock is driven by [clock]. */
fun testSnowflakeIdGenerator(
    clock: TestClock,
    workerId: Int = 0,
    datacenterId: Int = 0,
    epochStart: Instant = Instant.EPOCH,
): SnowflakeIdGenerator = object : SnowflakeIdGenerator(
    workerId = workerId,
    datacenterId = datacenterId,
    epochStart = epochStart,
) {
    override fun currentEpochMillis(): Long = clock.now()
}

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
): FlakeIdGenerator = object : FlakeIdGenerator(
    timestampBits = timestampBits,
    datacenterIdBits = datacenterIdBits,
    workerIdBits = workerIdBits,
    timestampDivisor = timestampDivisor,
    epochStart = epochStart,
    datacenterId = datacenterId,
    workerId = workerId,
) {
    override fun currentEpochMillis(): Long = clock.now()
}

/** ULID generator whose clock is driven by [clock]. Randomness is still non-deterministic. */
fun testUlidIdGenerator(clock: TestClock = TestClock()): UlidIdGenerator =
    object : UlidIdGenerator() {
        override fun currentEpochMillis(): Long = clock.now()
    }

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
    object : UlidIdGenerator() {
        override fun currentEpochMillis(): Long = clock.now()
        override fun drawRandomness(): LongArray = longArrayOf(0L, 0L)
    }

/** UUID v7 generator whose clock is driven by [clock]. */
fun testUuidV7IdGenerator(clock: TestClock = TestClock()): UuidV7IdGenerator =
    object : UuidV7IdGenerator() {
        override fun currentEpochMillis(): Long = clock.now()
    }
