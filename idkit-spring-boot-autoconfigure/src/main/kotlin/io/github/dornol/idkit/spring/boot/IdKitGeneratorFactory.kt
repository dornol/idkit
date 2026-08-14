package io.github.dornol.idkit.spring.boot

import io.github.dornol.idkit.IdGenerator
import io.github.dornol.idkit.flake.FlakeIdGenerator
import io.github.dornol.idkit.flake.SnowflakeIdGenerator
import io.github.dornol.idkit.worker.WorkerIdLease

internal object IdKitGeneratorFactory {
    fun validate(properties: IdKitProperties) {
        val generator = properties.generator
        val workerBits = if (generator.type == IdKitProperties.Type.SNOWFLAKE) 5 else generator.workerIdBits
        val datacenterBits = if (generator.type == IdKitProperties.Type.SNOWFLAKE) 5 else generator.datacenterIdBits
        if (generator.type == IdKitProperties.Type.FLAKE) {
            require(generator.timestampBits > 0) { "idkit.generator.timestamp-bits must be > 0" }
            require(generator.timestampDivisor > 0) { "idkit.generator.timestamp-divisor must be > 0" }
            require(generator.datacenterIdBits in 1..5) {
                "idkit.generator.datacenter-id-bits must be between 1 and 5"
            }
            require(generator.workerIdBits in 1..31) {
                "idkit.generator.worker-id-bits must be between 1 and 31"
            }
            require(1 + generator.timestampBits + generator.datacenterIdBits + generator.workerIdBits <= 63) {
                "idkit.generator bit layout must leave at least one bit for the sequence"
            }
        }
        require(properties.workerCount.toLong() <= (1L shl workerBits)) {
            "idkit.worker-count exceeds the configured worker-id capacity"
        }
        require(properties.datacenterId.toLong() < (1L shl datacenterBits)) {
            "idkit.datacenter-id exceeds the configured datacenter-id capacity"
        }
    }

    fun create(lease: WorkerIdLease, properties: IdKitProperties): IdGenerator<Long> {
        val generator = properties.generator
        return when (generator.type) {
            IdKitProperties.Type.SNOWFLAKE -> SnowflakeIdGenerator(
                workerId = lease.workerId,
                datacenterId = lease.datacenterId,
                epochStart = generator.epoch,
                clockRegressionTolerance = generator.clockRegressionTolerance,
            )
            IdKitProperties.Type.FLAKE -> FlakeIdGenerator(
                timestampBits = generator.timestampBits,
                datacenterIdBits = generator.datacenterIdBits,
                workerIdBits = generator.workerIdBits,
                timestampDivisor = generator.timestampDivisor,
                epochStart = generator.epoch,
                datacenterId = lease.datacenterId,
                workerId = lease.workerId,
                clockRegressionTolerance = generator.clockRegressionTolerance,
            )
        }
    }
}
