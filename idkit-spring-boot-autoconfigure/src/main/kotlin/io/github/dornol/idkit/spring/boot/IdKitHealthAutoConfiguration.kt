package io.github.dornol.idkit.spring.boot

import io.github.dornol.idkit.worker.WorkerIdLease
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

@AutoConfiguration(after = [JdbcIdKitAutoConfiguration::class, RedisIdKitAutoConfiguration::class])
@ConditionalOnClass(HealthIndicator::class)
@ConditionalOnBean(WorkerIdLease::class)
@ConditionalOnProperty(prefix = "idkit.health", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class IdKitHealthAutoConfiguration {
    @Bean
    fun idKitHealthIndicator(lease: WorkerIdLease): HealthIndicator =
        HealthIndicator {
            if (lease.isValid) {
                Health.up()
                    .withDetail("workerId", lease.workerId)
                    .withDetail("datacenterId", lease.datacenterId)
                    .withDetail("fencingToken", lease.fencingToken)
                    .build()
            } else {
                Health.down()
                    .withDetail("workerId", lease.workerId)
                    .withDetail("datacenterId", lease.datacenterId)
                    .withDetail("reason", "worker identity lease is no longer valid")
                    .build()
            }
        }
}
