package io.github.dornol.idkit.spring.boot

import io.github.dornol.idkit.IdGenerator
import io.github.dornol.idkit.jdbc.JdbcLeaseDialect
import io.github.dornol.idkit.jdbc.JdbcLeaseFailureListener
import io.github.dornol.idkit.jdbc.JdbcLeaseMetrics
import io.github.dornol.idkit.jdbc.JdbcWorkerIdLeaseStore
import io.github.dornol.idkit.jdbc.NoopJdbcLeaseMetrics
import io.github.dornol.idkit.worker.LeasedIdGenerator
import io.github.dornol.idkit.worker.SystemLeaseClock
import io.github.dornol.idkit.worker.WorkerIdLease
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import javax.sql.DataSource

@AutoConfiguration(after = [DataSourceAutoConfiguration::class])
@ConditionalOnClass(JdbcWorkerIdLeaseStore::class)
@ConditionalOnBean(DataSource::class)
@ConditionalOnProperty(prefix = "idkit", name = ["backend"], havingValue = "jdbc")
@EnableConfigurationProperties(IdKitProperties::class)
class JdbcIdKitAutoConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean(name = ["idKitScheduler"], destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = ["idKitScheduler"])
    fun idKitScheduler(): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "idkit-lease-heartbeat").apply { isDaemon = true }
        }

    @Bean(destroyMethod = "close")
    fun jdbcWorkerIdLeaseStore(
        dataSource: DataSource,
        idKitScheduler: ScheduledExecutorService,
        properties: IdKitProperties,
        metrics: JdbcLeaseMetrics,
    ): JdbcWorkerIdLeaseStore = JdbcWorkerIdLeaseStore(
        dataSource = dataSource,
        scheduler = idKitScheduler,
        dialect = properties.jdbc.dialect.toDialect(),
        tableName = properties.jdbc.tableName,
        failureListener = JdbcLeaseFailureListener { workerId, datacenterId, cause ->
            log.error("idkit JDBC worker lease lost: workerId={}, datacenterId={}", workerId, datacenterId, cause)
        },
        metrics = metrics,
        clock = SystemLeaseClock,
    )

    @Bean
    @ConditionalOnMissingBean(JdbcLeaseMetrics::class)
    fun jdbcLeaseMetrics(): JdbcLeaseMetrics = NoopJdbcLeaseMetrics

    @Bean(destroyMethod = "close")
    fun idKitWorkerIdLease(
        store: JdbcWorkerIdLeaseStore,
        properties: IdKitProperties,
    ): WorkerIdLease {
        validate(properties)
        IdKitGeneratorFactory.validate(properties)
        if (properties.jdbc.autoInitialize) {
            store.initialize(properties.workerCount, properties.datacenterId)
        }
        return acquire(store, properties)
    }

    @Bean
    @ConditionalOnMissingBean(IdGenerator::class)
    fun idGenerator(
        lease: WorkerIdLease,
        properties: IdKitProperties,
    ): IdGenerator<Long> {
        return LeasedIdGenerator(
            IdKitGeneratorFactory.create(lease, properties),
            lease,
        )
    }

    private fun acquire(store: JdbcWorkerIdLeaseStore, properties: IdKitProperties): WorkerIdLease {
        for (workerId in 0 until properties.workerCount) {
            store.tryAcquire(
                workerId = workerId,
                datacenterId = properties.datacenterId,
                owner = properties.owner,
                ttlMillis = properties.leaseTtl.toMillis(),
            )?.let { return it }
        }
        error("No idkit JDBC worker identity is available: workerCount=" + properties.workerCount + ", datacenterId=" + properties.datacenterId)
    }

    private fun validate(properties: IdKitProperties) {
        require(properties.workerCount > 0) { "idkit.worker-count must be > 0" }
        require(properties.datacenterId >= 0) { "idkit.datacenter-id must be >= 0" }
        require(properties.owner.isNotBlank()) { "idkit.owner must not be blank" }
        require(!properties.leaseTtl.isZero && !properties.leaseTtl.isNegative) { "idkit.lease-ttl must be positive" }
    }

    private fun IdKitProperties.Dialect.toDialect(): JdbcLeaseDialect = when (this) {
        IdKitProperties.Dialect.POSTGRESQL -> JdbcLeaseDialect.POSTGRESQL
        IdKitProperties.Dialect.MYSQL -> JdbcLeaseDialect.MYSQL
        IdKitProperties.Dialect.MARIADB -> JdbcLeaseDialect.MARIADB
        IdKitProperties.Dialect.MSSQL -> JdbcLeaseDialect.MSSQL
        IdKitProperties.Dialect.ORACLE -> JdbcLeaseDialect.ORACLE
    }
}
