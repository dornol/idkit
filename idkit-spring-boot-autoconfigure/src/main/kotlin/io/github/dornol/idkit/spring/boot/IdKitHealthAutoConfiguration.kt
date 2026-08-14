package io.github.dornol.idkit.spring.boot

import io.github.dornol.idkit.worker.WorkerIdLease
import io.github.dornol.idkit.worker.LeaseRecoveryStatus
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.beans.factory.ObjectProvider

@AutoConfiguration(after = [JdbcIdKitAutoConfiguration::class, RedisIdKitAutoConfiguration::class])
@ConditionalOnClass(HealthIndicator::class)
@ConditionalOnBean(WorkerIdLease::class)
@ConditionalOnProperty(prefix = "idkit.health", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class IdKitHealthAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["idKitHealthIndicator"])
    fun idKitHealthIndicator(
        lease: WorkerIdLease,
        recoveryStatus: ObjectProvider<LeaseRecoveryStatus>,
    ): HealthIndicator =
        HealthIndicator {
            val status = recoveryStatus.ifAvailable
            val currentLease = status?.currentLease ?: lease
            val remainingTtl = currentLease.remainingTtlMillis
            if (currentLease.isValid && (remainingTtl == null || remainingTtl > 0)) {
                val builder = Health.up()
                    .withDetail("workerId", currentLease.workerId)
                    .withDetail("datacenterId", currentLease.datacenterId)
                    .withDetail("fencingToken", currentLease.fencingToken)
                status?.let { builder.withDetail("recovering", it.isRecovering) }
                remainingTtl?.let { builder.withDetail("remainingTtlMillis", it) }
                builder.build()
            } else {
                Health.down()
                    .withDetail("workerId", currentLease.workerId)
                    .withDetail("datacenterId", currentLease.datacenterId)
                    .withDetail(
                        "reason",
                        if (status?.isRecovering == true) "worker identity lease recovery is in progress"
                        else if (!currentLease.isValid) "worker identity lease is no longer valid"
                        else "worker identity lease TTL has elapsed",
                    )
                    .build()
            }
        }
}
