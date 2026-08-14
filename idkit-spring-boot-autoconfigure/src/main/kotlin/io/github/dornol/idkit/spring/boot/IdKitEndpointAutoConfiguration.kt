package io.github.dornol.idkit.spring.boot

import io.github.dornol.idkit.IdGenerator
import io.github.dornol.idkit.worker.LeaseRecoveryStatus
import io.github.dornol.idkit.worker.WorkerIdLease
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.beans.factory.ObjectProvider

@AutoConfiguration(
    afterName = [
        "io.github.dornol.idkit.spring.boot.JdbcIdKitAutoConfiguration",
        "io.github.dornol.idkit.spring.boot.RedisIdKitAutoConfiguration",
        "io.github.dornol.idkit.spring.boot.LocalIdKitAutoConfiguration",
    ],
)
@ConditionalOnClass(Endpoint::class)
@ConditionalOnBean(IdGenerator::class)
@ConditionalOnProperty(prefix = "idkit.endpoint", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class IdKitEndpointAutoConfiguration {
    @Bean
    fun idKitEndpoint(
        generator: IdGenerator<*>,
        lease: ObjectProvider<WorkerIdLease>,
        recovery: ObjectProvider<LeaseRecoveryStatus>,
    ): IdKitEndpoint = IdKitEndpoint(generator, lease.ifAvailable, recovery.ifAvailable)
}

@Endpoint(id = "idkit")
class IdKitEndpoint(
    private val generator: IdGenerator<*>,
    private val lease: WorkerIdLease?,
    private val recovery: LeaseRecoveryStatus?,
) {
    @ReadOperation
    fun info(): Map<String, Any?> = buildMap {
        put("generator", generator.javaClass.name)
        lease?.let {
            put("workerId", it.workerId)
            put("datacenterId", it.datacenterId)
            put("fencingToken", it.fencingToken)
            put("leaseValid", it.isValid)
            put("remainingTtlMillis", it.remainingTtlMillis)
        }
        recovery?.let {
            put("recovering", it.isRecovering)
            put("recoveryAttempts", it.recoveryAttempts)
            put("recoveryFailures", it.recoveryFailures)
            put("lastRecoveryFailure", it.lastRecoveryFailure?.message)
        }
    }
}
