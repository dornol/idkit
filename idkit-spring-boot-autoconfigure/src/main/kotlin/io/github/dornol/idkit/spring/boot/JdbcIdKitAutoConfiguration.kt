package io.github.dornol.idkit.spring.boot

import io.github.dornol.idkit.IdGenerator
import io.github.dornol.idkit.jdbc.JdbcLeaseDialect
import io.github.dornol.idkit.jdbc.JdbcLeaseFailureListener
import io.github.dornol.idkit.jdbc.JdbcLeaseMetrics
import io.github.dornol.idkit.jdbc.JdbcWorkerIdLeaseStore
import io.github.dornol.idkit.jdbc.NoopJdbcLeaseMetrics
import io.github.dornol.idkit.worker.LeasedIdGenerator
import io.github.dornol.idkit.worker.LeaseRecoveryMetrics
import io.github.dornol.idkit.worker.NoopLeaseRecoveryMetrics
import io.github.dornol.idkit.worker.RecoveringLeasedIdGenerator
import io.github.dornol.idkit.worker.SystemLeaseClock
import io.github.dornol.idkit.worker.WorkerIdLease
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.ApplicationContext
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import javax.sql.DataSource

@AutoConfiguration(
    // Spring Boot 4 moved DataSourceAutoConfiguration to org.springframework.boot.jdbc.autoconfigure.
    // Name-based ordering keeps this compatible with both Boot 3 and Boot 4 without eagerly
    // linking against a class that does not exist in the other Boot generation.
    afterName = [
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    ],
)
@ConditionalOnClass(JdbcWorkerIdLeaseStore::class)
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

    @Bean(name = ["idKitRecoveryScheduler"], destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = ["idKitRecoveryScheduler"])
    fun idKitRecoveryScheduler(): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "idkit-lease-recovery").apply { isDaemon = true }
        }

    @Bean(destroyMethod = "close")
    @ConditionalOnBean(DataSource::class)
    @ConditionalOnMissingBean(JdbcWorkerIdLeaseStore::class)
    fun jdbcWorkerIdLeaseStore(
        applicationContext: ApplicationContext,
        @org.springframework.beans.factory.annotation.Qualifier("idKitScheduler")
        idKitScheduler: ScheduledExecutorService,
        properties: IdKitProperties,
        metrics: JdbcLeaseMetrics,
    ): JdbcWorkerIdLeaseStore = JdbcWorkerIdLeaseStore(
        dataSource = applicationContext.idKitDataSource(properties),
        scheduler = idKitScheduler,
        dialect = properties.jdbc.dialect.toDialect(),
        tableName = properties.jdbc.tableNameForNamespace(properties.leaseNamespace),
        failureListener = JdbcLeaseFailureListener { workerId, datacenterId, cause ->
            log.error("idkit JDBC worker lease lost: workerId={}, datacenterId={}", workerId, datacenterId, cause)
        },
        metrics = metrics,
        heartbeatFailureThreshold = properties.heartbeatFailureThreshold,
        clockSkewAllowanceMillis = properties.jdbc.clockSkewAllowance.toMillis(),
        statementTimeoutSeconds = properties.backendOperationTimeout.seconds.coerceAtLeast(1).toInt(),
        heartbeatIntervalMillis = properties.heartbeatInterval?.toMillis(),
        clock = SystemLeaseClock,
    )

    @Bean
    @ConditionalOnMissingBean(JdbcLeaseMetrics::class)
    fun jdbcLeaseMetrics(): JdbcLeaseMetrics = NoopJdbcLeaseMetrics

    @Bean
    @ConditionalOnMissingBean(LeaseRecoveryMetrics::class)
    fun leaseRecoveryMetrics(): LeaseRecoveryMetrics = NoopLeaseRecoveryMetrics

    @Bean(destroyMethod = "close")
    @ConditionalOnBean(JdbcWorkerIdLeaseStore::class)
    fun idKitWorkerIdLease(
        store: JdbcWorkerIdLeaseStore,
        properties: IdKitProperties,
    ): WorkerIdLease {
        IdKitAutoConfigurationSupport.validateCommon(properties)
        IdKitAutoConfigurationSupport.validateJdbc(properties)
        IdKitGeneratorFactory.validate(properties)
        IdKitAutoConfigurationSupport.sleepStartupJitter(properties.startupJitter.toMillis())
        if (properties.jdbc.autoInitialize) {
            store.initialize(properties.workerCount, properties.datacenterId)
        }
        if (properties.jdbc.validateSchema) {
            store.validateSchema(properties.workerCount, properties.datacenterId)
        }
        return acquire(store, properties)
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnBean(JdbcWorkerIdLeaseStore::class)
    @ConditionalOnMissingBean(IdGenerator::class)
    fun idGenerator(
        lease: WorkerIdLease,
        properties: IdKitProperties,
        store: JdbcWorkerIdLeaseStore,
        @org.springframework.beans.factory.annotation.Qualifier("idKitScheduler")
        idKitScheduler: ScheduledExecutorService,
        @org.springframework.beans.factory.annotation.Qualifier("idKitRecoveryScheduler")
        idKitRecoveryScheduler: ScheduledExecutorService,
        recoveryMetrics: LeaseRecoveryMetrics,
    ): IdGenerator<Long> {
        val factory = { acquired: WorkerIdLease -> IdKitGeneratorFactory.create(acquired, properties) }
        if (!properties.recovery.enabled) return LeasedIdGenerator(factory(lease), lease)
        return RecoveringLeasedIdGenerator(
            initialLease = lease,
            initialGenerator = factory(lease),
            scheduler = idKitScheduler,
            recoveryScheduler = idKitRecoveryScheduler,
            recoveryRetryDelayMillis = properties.recovery.retryDelay.toMillis(),
            recoveryRetryJitterMillis = properties.recovery.retryJitter.toMillis(),
            recoveryMaxRetryDelayMillis = properties.recovery.maxRetryDelay.toMillis(),
            acquire = { acquire(store, properties) },
            generatorFactory = factory,
            metrics = recoveryMetrics,
        )
    }

    private fun acquire(store: JdbcWorkerIdLeaseStore, properties: IdKitProperties): WorkerIdLease {
        return IdKitAutoConfigurationSupport.acquireConfigured(
            properties = properties,
            backend = "JDBC",
            tryAcquire = { workerId ->
                store.tryAcquire(
                    workerId = workerId,
                    datacenterId = properties.datacenterId,
                    owner = properties.owner,
                    ttlMillis = properties.leaseTtl.toMillis(),
                )
            },
            onFailure = { attempt, retrying, failure ->
                log.warn("idkit JDBC lease acquisition attempt {} failed; retrying={}", attempt, retrying, failure)
            },
        )
    }

    private fun IdKitProperties.Jdbc.tableNameForNamespace(namespace: String?): String =
        namespace?.let { "${tableName}_$it" } ?: tableName

    private fun ApplicationContext.idKitDataSource(properties: IdKitProperties): DataSource =
        properties.jdbc.dataSourceBeanName?.let { getBean(it, DataSource::class.java) }
            ?: getBean(DataSource::class.java)

    private fun validateHeartbeatInterval(properties: IdKitProperties) {
        val interval = properties.heartbeatInterval ?: return
        require(!interval.isZero && !interval.isNegative) {
            "idkit.heartbeat-interval must be positive"
        }
        val intervalMillis = interval.toMillis()
        require(
            intervalMillis > 0 &&
                    runCatching {
                        Math.multiplyExact(intervalMillis, properties.heartbeatFailureThreshold.toLong())
                    }.getOrDefault(Long.MAX_VALUE) < properties.leaseTtl.toMillis()
        ) {
            "idkit.heartbeat-interval and heartbeat-failure-threshold must detect lease loss before lease-ttl"
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
