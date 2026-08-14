package io.github.dornol.idkit

import io.github.dornol.idkit.flake.FlakeIdGenerator
import io.github.dornol.idkit.flake.SnowflakeIdGenerator
import io.github.dornol.idkit.worker.LeasedIdGenerator
import io.github.dornol.idkit.worker.WorkerIdLease
import java.time.Duration
import java.time.Instant

/** Java-friendly factories for the built-in ID generators. */
object IdKitGenerators {
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
