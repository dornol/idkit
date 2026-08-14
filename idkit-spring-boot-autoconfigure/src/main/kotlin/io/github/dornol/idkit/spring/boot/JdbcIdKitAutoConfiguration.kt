package io.github.dornol.idkit.spring.boot

import io.github.dornol.idkit.IdGenerator
import io.github.dornol.idkit.jdbc.JdbcLeaseDialect
import io.github.dornol.idkit.jdbc.JdbcLeaseFailureListener
import io.github.dornol.idkit.jdbc.JdbcLeaseMetrics
import io.github.dornol.idkit.jdbc.JdbcWorkerIdLeaseStore
import io.github.dornol.idkit.jdbc.NoopJdbcLeaseMetrics
import io.github.dornol.idkit.worker.LeasedIdGenerator
import io.github.dornol.idkit.worker.RecoveringLeasedIdGenerator
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
    @ConditionalOnMissingBean(JdbcWorkerIdLeaseStore::class)
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
        heartbeatFailureThreshold = properties.heartbeatFailureThreshold,
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
        if (properties.jdbc.validateSchema) {
            store.validateSchema(properties.workerCount, properties.datacenterId)
        }
        return acquire(store, properties)
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(IdGenerator::class)
    fun idGenerator(
        lease: WorkerIdLease,
        properties: IdKitProperties,
        store: JdbcWorkerIdLeaseStore,
        idKitScheduler: ScheduledExecutorService,
    ): IdGenerator<Long> {
        val factory = { acquired: WorkerIdLease -> IdKitGeneratorFactory.create(acquired, properties) }
        if (!properties.recovery.enabled) return LeasedIdGenerator(factory(lease), lease)
        return RecoveringLeasedIdGenerator(
            initialLease = lease,
            initialGenerator = factory(lease),
            scheduler = idKitScheduler,
            recoveryRetryDelayMillis = properties.recovery.retryDelay.toMillis(),
            acquire = { acquire(store, properties) },
            generatorFactory = factory,
        )
    }

    private fun acquire(store: JdbcWorkerIdLeaseStore, properties: IdKitProperties): WorkerIdLease {
        val delayMillis = properties.acquisitionRetryDelay.toMillis()
        var lastFailure: Throwable? = null
        repeat(properties.acquisitionAttempts) { attempt ->
            try {
                for (workerId in 0 until properties.workerCount) {
                    store.tryAcquire(
                        workerId = workerId,
                        datacenterId = properties.datacenterId,
                        owner = properties.owner,
                        ttlMillis = properties.leaseTtl.toMillis(),
                    )?.let { return it }
                }
            } catch (failure: RuntimeException) {
                lastFailure = failure
                log.warn("idkit JDBC lease acquisition attempt {} failed; retrying={}", attempt + 1, attempt + 1 < properties.acquisitionAttempts, failure)
            }
            if (attempt + 1 < properties.acquisitionAttempts) sleepBeforeRetry(delayMillis)
        }
        error(
            "No idkit JDBC worker identity is available or the lease backend did not recover: " +
                    "workerCount=${properties.workerCount}, datacenterId=${properties.datacenterId}" +
                    (lastFailure?.let { "; lastFailure=${it.message}" } ?: ""),
        )
    }

    private fun sleepBeforeRetry(delayMillis: Long) {
        if (delayMillis == 0L) return
        try {
            Thread.sleep(delayMillis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while retrying JDBC worker lease acquisition", interrupted)
        }
    }

    private fun validate(properties: IdKitProperties) {
        require(properties.workerCount > 0) { "idkit.worker-count must be > 0" }
        require(properties.datacenterId >= 0) { "idkit.datacenter-id must be >= 0" }
        require(properties.owner.isNotBlank()) { "idkit.owner must not be blank" }
        require(!properties.leaseTtl.isZero && !properties.leaseTtl.isNegative) { "idkit.lease-ttl must be positive" }
        require(properties.acquisitionAttempts in 1..10) { "idkit.acquisition-attempts must be between 1 and 10" }
        require(!properties.acquisitionRetryDelay.isNegative) { "idkit.acquisition-retry-delay must not be negative" }
        require(!properties.recovery.retryDelay.isZero && !properties.recovery.retryDelay.isNegative) {
            "idkit.recovery.retry-delay must be positive"
        }
        require(properties.heartbeatFailureThreshold in 1..2) {
            "idkit.heartbeat-failure-threshold must be between 1 and 2 so the lease fails before TTL expiry"
        }
    }

    private fun IdKitProperties.Dialect.toDialect(): JdbcLeaseDialect = when (this) {
        IdKitProperties.Dialect.POSTGRESQL -> JdbcLeaseDialect.POSTGRESQL
        IdKitProperties.Dialect.MYSQL -> JdbcLeaseDialect.MYSQL
        IdKitProperties.Dialect.MARIADB -> JdbcLeaseDialect.MARIADB
        IdKitProperties.Dialect.MSSQL -> JdbcLeaseDialect.MSSQL
        IdKitProperties.Dialect.ORACLE -> JdbcLeaseDialect.ORACLE
    }
}
