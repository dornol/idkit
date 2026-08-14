package io.github.dornol.idkit.spring.boot

import io.github.dornol.idkit.redis.MicrometerRedisLeaseMetrics
import io.github.dornol.idkit.redis.NoopRedisLeaseMetrics
import io.github.dornol.idkit.redis.RedisLeaseMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

@AutoConfiguration(before = [RedisIdKitAutoConfiguration::class])
@ConditionalOnClass(MeterRegistry::class)
@ConditionalOnBean(MeterRegistry::class)
@ConditionalOnProperty(prefix = "idkit", name = ["backend"], havingValue = "redis")
class RedisMicrometerIdKitAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(RedisLeaseMetrics::class)
    fun redisLeaseMetrics(
        registry: MeterRegistry,
        properties: IdKitProperties,
    ): RedisLeaseMetrics = if (properties.metrics.enabled) {
        MicrometerRedisLeaseMetrics(registry, "\${properties.metrics.prefix}.redis.lease")
    } else {
        NoopRedisLeaseMetrics
    }
}
