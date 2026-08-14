package io.github.dornol.idkit.spring.boot

import io.github.dornol.idkit.jdbc.JdbcLeaseMetrics
import io.github.dornol.idkit.jdbc.MicrometerJdbcLeaseMetrics
import io.github.dornol.idkit.jdbc.NoopJdbcLeaseMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

@AutoConfiguration(before = [JdbcIdKitAutoConfiguration::class])
@ConditionalOnClass(MeterRegistry::class)
@ConditionalOnBean(MeterRegistry::class)
@ConditionalOnProperty(prefix = "idkit", name = ["backend"], havingValue = "jdbc")
class JdbcMicrometerIdKitAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(JdbcLeaseMetrics::class)
    fun jdbcLeaseMetrics(
        registry: MeterRegistry,
        properties: IdKitProperties,
    ): JdbcLeaseMetrics = if (properties.metrics.enabled) {
        MicrometerJdbcLeaseMetrics(registry, "\${properties.metrics.prefix}.jdbc.lease")
    } else {
        NoopJdbcLeaseMetrics
    }
}
