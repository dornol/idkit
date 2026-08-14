package io.github.dornol.idkit.spring.boot

import io.github.dornol.idkit.worker.WorkerIdLease
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

@AutoConfiguration(after = [JdbcIdKitAutoConfiguration::class, RedisIdKitAutoConfiguration::class])
@ConditionalOnClass(HealthIndicator::class)
@ConditionalOnBean(WorkerIdLease::class)
@ConditionalOnProperty(prefix = "idkit.health", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class IdKitHealthAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["idKitHealthIndicator"])
    fun idKitHealthIndicator(lease: WorkerIdLease): HealthIndicator =
        HealthIndicator {
            val remainingTtl = lease.remainingTtlMillis
            if (lease.isValid && (remainingTtl == null || remainingTtl > 0)) {
                val builder = Health.up()
                    .withDetail("workerId", lease.workerId)
                    .withDetail("datacenterId", lease.datacenterId)
                    .withDetail("fencingToken", lease.fencingToken)
                remainingTtl?.let { builder.withDetail("remainingTtlMillis", it) }
                builder.build()
            } else {
                Health.down()
                    .withDetail("workerId", lease.workerId)
                    .withDetail("datacenterId", lease.datacenterId)
                    .withDetail(
                        "reason",
                        if (!lease.isValid) "worker identity lease is no longer valid" else "worker identity lease TTL has elapsed",
                    )
                    .build()
            }
        }
}
