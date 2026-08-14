package io.github.dornol.idkit

import io.github.dornol.idkit.flake.FlakeIdGenerator
import io.github.dornol.idkit.flake.SnowflakeIdGenerator
import io.github.dornol.idkit.nanoid.NanoIdGenerator
import io.github.dornol.idkit.ulid.UlidIdGenerator
import io.github.dornol.idkit.uuidv7.UuidV7IdGenerator
import io.github.dornol.idkit.worker.LeasedIdGenerator
import io.github.dornol.idkit.worker.WorkerIdLease
import java.time.Duration
import java.time.Instant

/** Java-friendly factories for the built-in ID generators. */
object IdKitGenerators {
    /** Creates a UUID v7 generator with the default UTC clock. */
    @JvmStatic
    fun uuidV7(): UuidV7IdGenerator = UuidV7IdGenerator()

    /** Creates a monotonic ULID generator with the default UTC clock. */
    @JvmStatic
    fun ulid(): UlidIdGenerator = UlidIdGenerator()

    /** Creates a NanoID generator with the default size and URL-safe alphabet. */
    @JvmStatic
    fun nanoId(): NanoIdGenerator = NanoIdGenerator()

    /** Creates a NanoID generator with a custom size and alphabet. */
    @JvmStatic
    fun nanoId(size: Int, alphabet: String): NanoIdGenerator = NanoIdGenerator(size, alphabet)

    @JvmStatic
    fun snowflake(workerId: Int, datacenterId: Int): SnowflakeIdGenerator =
        SnowflakeIdGenerator.create(workerId, datacenterId)

    @JvmStatic
    fun snowflake(lease: WorkerIdLease): LeasedIdGenerator<Long> =
        LeasedIdGenerator(snowflake(lease.workerId, lease.datacenterId), lease)

    @JvmStatic
    @JvmOverloads
    fun flake(
        workerId: Int,
        datacenterId: Int,
        timestampBits: Int,
        datacenterIdBits: Int,
        workerIdBits: Int,
        timestampDivisor: Long = 1L,
        epochStart: Instant = Instant.EPOCH,
        clockRegressionTolerance: Duration = FlakeIdGenerator.DEFAULT_CLOCK_REGRESSION_TOLERANCE,
    ): FlakeIdGenerator = FlakeIdGenerator(
        timestampBits = timestampBits,
        datacenterIdBits = datacenterIdBits,
        workerIdBits = workerIdBits,
        timestampDivisor = timestampDivisor,
        epochStart = epochStart,
        datacenterId = datacenterId,
        workerId = workerId,
        clockRegressionTolerance = clockRegressionTolerance,
    )

    @JvmStatic
    @JvmOverloads
    fun flake(
        lease: WorkerIdLease,
        timestampBits: Int,
        datacenterIdBits: Int,
        workerIdBits: Int,
        timestampDivisor: Long = 1L,
        epochStart: Instant = Instant.EPOCH,
        clockRegressionTolerance: Duration = FlakeIdGenerator.DEFAULT_CLOCK_REGRESSION_TOLERANCE,
    ): LeasedIdGenerator<Long> = LeasedIdGenerator(
        flake(
            workerId = lease.workerId,
            datacenterId = lease.datacenterId,
            timestampBits = timestampBits,
            datacenterIdBits = datacenterIdBits,
            workerIdBits = workerIdBits,
            timestampDivisor = timestampDivisor,
            epochStart = epochStart,
            clockRegressionTolerance = clockRegressionTolerance,
        ),
        lease,
    )
}
