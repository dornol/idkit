package io.github.dornol.idkit.spring.boot

import io.github.dornol.idkit.IdGenerator
import io.github.dornol.idkit.redis.NoopRedisLeaseMetrics
import io.github.dornol.idkit.redis.RedisLeaseFailureListener
import io.github.dornol.idkit.redis.RedisLeaseMetrics
import io.github.dornol.idkit.redis.RedisWorkerIdLeaseStore
import io.github.dornol.idkit.worker.LeasedIdGenerator
import io.github.dornol.idkit.worker.LeaseRecoveryMetrics
import io.github.dornol.idkit.worker.NoopLeaseRecoveryMetrics
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
import io.lettuce.core.RedisURI

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

    @Bean(name = ["idKitRecoveryScheduler"], destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = ["idKitRecoveryScheduler"])
    fun idKitRecoveryScheduler(): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "idkit-lease-recovery").apply { isDaemon = true }
        }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = ["idKitRedisClient"])
    fun idKitRedisClient(properties: IdKitProperties): RedisClient {
        val uri = RedisURI.create(properties.redis.uri)
        uri.timeout = properties.backendOperationTimeout
        return RedisClient.create(uri)
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["idKitRedisConnection"])
    fun idKitRedisConnection(
        @org.springframework.beans.factory.annotation.Qualifier("idKitRedisClient") client: RedisClient,
    ): StatefulRedisConnection<String, String> =
        client.connect()

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(RedisWorkerIdLeaseStore::class)
    fun redisWorkerIdLeaseStore(
        @org.springframework.beans.factory.annotation.Qualifier("idKitRedisConnection")
        connection: StatefulRedisConnection<String, String>,
        @org.springframework.beans.factory.annotation.Qualifier("idKitScheduler")
        idKitScheduler: ScheduledExecutorService,
        properties: IdKitProperties,
        metrics: RedisLeaseMetrics,
    ): RedisWorkerIdLeaseStore = RedisWorkerIdLeaseStore(
        commands = connection.sync(),
        scheduler = idKitScheduler,
        keyPrefix = properties.redis.keyPrefixForNamespace(properties.leaseNamespace),
        heartbeatFailureThreshold = properties.heartbeatFailureThreshold,
        heartbeatIntervalMillis = properties.heartbeatInterval?.toMillis(),
        failureListener = RedisLeaseFailureListener { workerId, datacenterId, cause ->
            log.error("idkit Redis worker lease lost: workerId={}, datacenterId={}", workerId, datacenterId, cause)
        },
        metrics = metrics,
    )

    @Bean
    @ConditionalOnMissingBean(RedisLeaseMetrics::class)
    fun redisLeaseMetrics(): RedisLeaseMetrics = NoopRedisLeaseMetrics

    @Bean
    @ConditionalOnMissingBean(LeaseRecoveryMetrics::class)
    fun leaseRecoveryMetrics(): LeaseRecoveryMetrics = NoopLeaseRecoveryMetrics

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

    private fun acquire(store: RedisWorkerIdLeaseStore, properties: IdKitProperties): WorkerIdLease {
        if (properties.workerId == null) {
            return store.acquireAny(
                workerCount = properties.workerCount,
                datacenterId = properties.datacenterId,
                owner = properties.owner,
                ttlMillis = properties.leaseTtl.toMillis(),
                acquisitionAttempts = properties.acquisitionAttempts,
                acquisitionRetryDelayMillis = properties.acquisitionRetryDelay.toMillis(),
            )
        }
        var lastFailure: Throwable? = null
        repeat(properties.acquisitionAttempts) { attempt ->
            try {
                store.tryAcquire(
                    workerId = properties.workerId!!,
                    datacenterId = properties.datacenterId,
                    owner = properties.owner,
                    ttlMillis = properties.leaseTtl.toMillis(),
                )?.let { return it }
            } catch (failure: RuntimeException) {
                lastFailure = failure
            }
            if (attempt + 1 < properties.acquisitionAttempts) {
                sleepBeforeRetry(properties.acquisitionRetryDelay.toMillis())
            }
        }
        error(
            "Configured idkit worker identity is unavailable: " +
                    "workerId=${properties.workerId}, datacenterId=${properties.datacenterId}" +
                    (lastFailure?.let { "; lastFailure=${it.message}" } ?: ""),
        )
    }

    private fun sleepBeforeRetry(delayMillis: Long) {
        if (delayMillis == 0L) return
        try {
            Thread.sleep(delayMillis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while retrying Redis worker lease acquisition", interrupted)
        }
    }

    private fun validate(properties: IdKitProperties) {
        validateLeaseNamespace(properties)
        require(properties.workerCount > 0) { "idkit.worker-count must be > 0" }
        require(properties.workerId == null || properties.workerId!! in 0 until properties.workerCount) {
            "idkit.worker-id must be between 0 and worker-count - 1"
        }
        require(properties.datacenterId >= 0) { "idkit.datacenter-id must be >= 0" }
        require(properties.owner.isNotBlank()) { "idkit.owner must not be blank" }
        require(!properties.leaseTtl.isZero && !properties.leaseTtl.isNegative) { "idkit.lease-ttl must be positive" }
        require(properties.heartbeatFailureThreshold in 1..2) {
            "idkit.heartbeat-failure-threshold must be between 1 and 2 so the lease fails before TTL expiry"
        }
        validateHeartbeatInterval(properties)
        require(!properties.backendOperationTimeout.isZero && !properties.backendOperationTimeout.isNegative) {
            "idkit.backend-operation-timeout must be positive"
        }
        require(properties.acquisitionAttempts in 1..10) { "idkit.acquisition-attempts must be between 1 and 10" }
        require(!properties.acquisitionRetryDelay.isNegative) { "idkit.acquisition-retry-delay must not be negative" }
        require(!properties.recovery.retryDelay.isZero && !properties.recovery.retryDelay.isNegative) {
            "idkit.recovery.retry-delay must be positive"
        }
        require(!properties.recovery.retryJitter.isNegative) {
            "idkit.recovery.retry-jitter must not be negative"
        }
        require(!properties.recovery.maxRetryDelay.isNegative && !properties.recovery.maxRetryDelay.isZero) {
            "idkit.recovery.max-retry-delay must be positive"
        }
        require(properties.recovery.maxRetryDelay >= properties.recovery.retryDelay) {
            "idkit.recovery.max-retry-delay must be >= retry-delay"
        }
    }

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

    private fun validateLeaseNamespace(properties: IdKitProperties) {
        require(properties.leaseNamespace?.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) != false) {
            "idkit.lease-namespace must be a simple identifier"
        }
    }

    private fun IdKitProperties.Redis.keyPrefixForNamespace(namespace: String?): String =
        namespace?.let { "${keyPrefix}:$it" } ?: keyPrefix
}
