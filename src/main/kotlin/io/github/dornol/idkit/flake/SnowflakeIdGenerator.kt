package io.github.dornol.idkit.flake

import io.github.dornol.idkit.IdGeneratorListener
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Snowflake ID Generator.
 *
 * Generates unique 64-bit Long ids using Twitter's Snowflake algorithm. The bit layout is
 * `timestamp(41) | datacenterId(5) | workerId(5) | sequence(12)`.
 *
 * @param workerId node (worker) id, in the range `0..31`.
 * @param datacenterId datacenter id, in the range `0..31`.
 * @param epochStart Snowflake epoch start; defaults to `1970-01-01T00:00:00Z`.
 * @param clockRegressionTolerance maximum wall-clock regression to absorb before throwing.
 *   Defaults to [FlakeIdGenerator.DEFAULT_CLOCK_REGRESSION_TOLERANCE] (10 ms); pass
 *   `Duration.ZERO` to restore the pre-3.0.0 strict fail-fast behavior.
 * @param clock time source. Defaults to `Clock.systemUTC()`. Inject a fake clock for
 *   deterministic tests; see `io.github.dornol.idkit.testing.TestClock`.
 * @param listener optional event listener. Defaults to [IdGeneratorListener.NOOP].
 *
 * @see <a href="https://github.com/twitter/snowflake/tree/snowflake-2010">Twitter Snowflake</a>
 */
open class SnowflakeIdGenerator(
    workerId: Int,
    datacenterId: Int,
    epochStart: Instant = Instant.EPOCH,
    clockRegressionTolerance: Duration = FlakeIdGenerator.DEFAULT_CLOCK_REGRESSION_TOLERANCE,
    clock: Clock = Clock.systemUTC(),
    listener: IdGeneratorListener = IdGeneratorListener.NOOP,
) : FlakeIdGenerator(
    timestampBits = 41,
    datacenterIdBits = 5,
    workerIdBits = 5,
    timestampDivisor = 1L,
    workerId = workerId,
    datacenterId = datacenterId,
    epochStart = epochStart,
    clockRegressionTolerance = clockRegressionTolerance,
    clock = clock,
    listener = listener,
)
