package io.github.dornol.idkit.spring.boot

import io.github.dornol.idkit.IdGenerator
import io.github.dornol.idkit.redis.NoopRedisLeaseMetrics
import io.github.dornol.idkit.redis.RedisLeaseFailureListener
import io.github.dornol.idkit.redis.RedisLeaseMetrics
import io.github.dornol.idkit.redis.RedisWorkerIdLeaseStore
import io.github.dornol.idkit.worker.LeasedIdGenerator
import io.github.dornol.idkit.worker.RecoveringLeasedIdGenerator
import io.github.dornol.idkit.worker.WorkerIdLease
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

@AutoConfiguration
@ConditionalOnClass(RedisWorkerIdLeaseStore::class)
@ConditionalOnProperty(prefix = "idkit", name = ["backend"], havingValue = "redis")
@EnableConfigurationProperties(IdKitProperties::class)
class RedisIdKitAutoConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean(name = ["idKitScheduler"], destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = ["idKitScheduler"])
    fun idKitScheduler(): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "idkit-lease-heartbeat").apply { isDaemon = true }
        }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(RedisClient::class)
    fun idKitRedisClient(properties: IdKitProperties): RedisClient =
        RedisClient.create(properties.redis.uri)

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(StatefulRedisConnection::class)
    fun idKitRedisConnection(client: RedisClient): StatefulRedisConnection<String, String> =
        client.connect()

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(RedisWorkerIdLeaseStore::class)
    fun redisWorkerIdLeaseStore(
        connection: StatefulRedisConnection<String, String>,
        idKitScheduler: ScheduledExecutorService,
        properties: IdKitProperties,
        metrics: RedisLeaseMetrics,
    ): RedisWorkerIdLeaseStore = RedisWorkerIdLeaseStore(
        commands = connection.sync(),
        scheduler = idKitScheduler,
        keyPrefix = properties.redis.keyPrefix,
        heartbeatFailureThreshold = properties.heartbeatFailureThreshold,
        failureListener = RedisLeaseFailureListener { workerId, datacenterId, cause ->
            log.error("idkit Redis worker lease lost: workerId={}, datacenterId={}", workerId, datacenterId, cause)
        },
        metrics = metrics,
    )

    @Bean
    @ConditionalOnMissingBean(RedisLeaseMetrics::class)
    fun redisLeaseMetrics(): RedisLeaseMetrics = NoopRedisLeaseMetrics

    @Bean(destroyMethod = "close")
    fun idKitWorkerIdLease(
        store: RedisWorkerIdLeaseStore,
        properties: IdKitProperties,
    ): WorkerIdLease {
        validate(properties)
        IdKitGeneratorFactory.validate(properties)
        return acquire(store, properties)
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(IdGenerator::class)
    fun idGenerator(
        lease: WorkerIdLease,
        properties: IdKitProperties,
        store: RedisWorkerIdLeaseStore,
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

    private fun acquire(store: RedisWorkerIdLeaseStore, properties: IdKitProperties): WorkerIdLease =
        store.acquireAny(
            workerCount = properties.workerCount,
            datacenterId = properties.datacenterId,
            owner = properties.owner,
            ttlMillis = properties.leaseTtl.toMillis(),
            acquisitionAttempts = properties.acquisitionAttempts,
            acquisitionRetryDelayMillis = properties.acquisitionRetryDelay.toMillis(),
        )

    private fun validate(properties: IdKitProperties) {
        require(properties.workerCount > 0) { "idkit.worker-count must be > 0" }
        require(properties.datacenterId >= 0) { "idkit.datacenter-id must be >= 0" }
        require(properties.owner.isNotBlank()) { "idkit.owner must not be blank" }
        require(!properties.leaseTtl.isZero && !properties.leaseTtl.isNegative) { "idkit.lease-ttl must be positive" }
        require(properties.heartbeatFailureThreshold in 1..2) {
            "idkit.heartbeat-failure-threshold must be between 1 and 2 so the lease fails before TTL expiry"
        }
        require(properties.acquisitionAttempts in 1..10) { "idkit.acquisition-attempts must be between 1 and 10" }
        require(!properties.acquisitionRetryDelay.isNegative) { "idkit.acquisition-retry-delay must not be negative" }
        require(!properties.recovery.retryDelay.isZero && !properties.recovery.retryDelay.isNegative) {
            "idkit.recovery.retry-delay must be positive"
        }
    }
}
