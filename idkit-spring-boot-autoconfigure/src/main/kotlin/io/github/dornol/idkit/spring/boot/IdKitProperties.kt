package io.github.dornol.idkit.spring.boot

import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("idkit")
class IdKitProperties {
    var backend: Backend? = null
    /** Optional namespace for isolating lease ownership between services sharing one backend. */
    var leaseNamespace: String? = null
    var workerCount: Int = 32
    /** Optional fixed worker slot. When absent, the first available slot is leased. */
    var workerId: Int? = null
    var datacenterId: Int = 0
    var owner: String = defaultOwner()
    var leaseTtl: Duration = Duration.ofSeconds(30)
    var heartbeatFailureThreshold: Int = 1
    /** Optional heartbeat interval; defaults to one-third of the lease TTL. */
    var heartbeatInterval: Duration? = null
    /** Random delay before startup lease acquisition; disabled by default. */
    var startupJitter: Duration = Duration.ZERO
    var backendOperationTimeout: Duration = Duration.ofSeconds(5)
    var acquisitionAttempts: Int = 3
    var acquisitionRetryDelay: Duration = Duration.ofSeconds(1)
    var recovery: Recovery = Recovery()
    var metrics: Metrics = Metrics()
    var generator: Generator = Generator()
    var jdbc: Jdbc = Jdbc()
    var redis: Redis = Redis()

    enum class Backend { JDBC, REDIS }

    class Metrics {
        var enabled: Boolean = true
        var prefix: String = "idkit.lease"
    }

    class Recovery {
        var enabled: Boolean = true
        var retryDelay: Duration = Duration.ofSeconds(1)
        var retryJitter: Duration = Duration.ofMillis(500)
        var maxRetryDelay: Duration = Duration.ofSeconds(30)
    }

    class Generator {
        var type: Type = Type.SNOWFLAKE
        var timestampBits: Int = 41
        var datacenterIdBits: Int = 5
        var workerIdBits: Int = 5
        var timestampDivisor: Long = 1L
        var epoch: Instant = Instant.EPOCH
        var clockRegressionTolerance: Duration = Duration.ofMillis(10)
    }

    enum class Type { SNOWFLAKE, FLAKE }

    class Jdbc {
        /**
         * Creates the lease table and missing fencing column on startup when enabled.
         *
         * DDL is opt-in so production applications that manage schema through migrations do
         * not unexpectedly require schema-altering privileges at startup.
         */
        var autoInitialize: Boolean = false
        /** Validates the managed table and worker rows at startup without changing schema. */
        var validateSchema: Boolean = false
        var dialect: Dialect = Dialect.POSTGRESQL
        var tableName: String = "idkit_worker_lease"
        /** Extra delay before another process may reuse a row, absorbing clock skew. */
        var clockSkewAllowance: Duration = Duration.ofSeconds(1)
        /** Optional Spring Bean name for the DataSource dedicated to IDKit. */
        var dataSourceBeanName: String? = null
    }

    class Redis {
        var uri: String = "redis://localhost:6379"
        var keyPrefix: String = "idkit:worker"
    }

    enum class Dialect { POSTGRESQL, MYSQL, MARIADB, MSSQL, ORACLE }

    companion object {
        private fun defaultOwner(): String =
            runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("idkit")
    }
}
