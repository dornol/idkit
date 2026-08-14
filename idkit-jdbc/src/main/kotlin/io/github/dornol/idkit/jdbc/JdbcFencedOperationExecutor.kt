package io.github.dornol.idkit.jdbc

import io.github.dornol.idkit.worker.FencedOperationExecutor
import io.github.dornol.idkit.worker.FencedOperationResult
import java.sql.Connection
import javax.sql.DataSource

/** Binds fencing-token advancement and a JDBC side effect to one transaction. */
class JdbcFencedOperationExecutor(
    private val dataSource: DataSource,
    private val dialect: JdbcLeaseDialect = JdbcLeaseDialect.POSTGRESQL,
    private val tableName: String = "idkit_fencing_token",
) : FencedOperationExecutor {
    init { require(tableName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "tableName must be a simple SQL identifier" } }

    fun initialize() = JdbcFencingTokenValidator(dataSource, dialect, tableName).initialize()

    /** The callback must use the supplied connection for the side effect. */
    fun executeWithConnection(resource: String, fencingToken: Long, operation: (Connection) -> Unit): FencedOperationResult {
        require(resource.isNotBlank()) { "resource must not be blank" }
        require(fencingToken > 0) { "fencingToken must be > 0" }
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val applied = if (dialect === JdbcLeaseDialect.MYSQL || dialect === JdbcLeaseDialect.MARIADB) {
                    advanceMySql(connection, resource, fencingToken)
                } else {
                    val current = connection.prepareStatement("SELECT fencing_token FROM $tableName WHERE resource_key = ?").use { statement ->
                        statement.setString(1, resource)
                        statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
                    }
                    if (current != null && fencingToken <= current) {
                        connection.rollback()
                        return FencedOperationResult.REJECTED_STALE
                    }
                    connection.prepareStatement(dialect.fencedOperationSql(tableName)).use { statement ->
                        statement.setString(1, resource)
                        statement.setLong(2, fencingToken)
                        statement.executeUpdate() > 0
                    }
                }
                // A zero-row compare-and-set means another owner already advanced the token,
                // including the race where this transaction observed no row before it was
                // inserted by a concurrent transaction.  Never execute the side effect then.
                if (!applied) {
                    connection.rollback()
                    return FencedOperationResult.REJECTED_STALE
                }
                operation(connection)
                connection.commit()
                return FencedOperationResult.APPLIED
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            }
        }
    }

    private fun advanceMySql(connection: Connection, resource: String, fencingToken: Long): Boolean {
        val inserted = connection.prepareStatement(
            "INSERT IGNORE INTO $tableName (resource_key, fencing_token) VALUES (?, ?)",
        ).use { statement ->
            statement.setString(1, resource)
            statement.setLong(2, fencingToken)
            statement.executeUpdate()
        }
        if (inserted > 0) return true

        val current = connection.prepareStatement(
            "SELECT fencing_token FROM $tableName WHERE resource_key = ? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, resource)
            statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
        }
        if (current == null || fencingToken <= current) return false
        return connection.prepareStatement(
            "UPDATE $tableName SET fencing_token = ? WHERE resource_key = ? AND fencing_token < ?",
        ).use { statement ->
            statement.setLong(1, fencingToken)
            statement.setString(2, resource)
            statement.setLong(3, fencingToken)
            statement.executeUpdate() == 1
        }
    }

    override fun execute(resource: String, fencingToken: Long, operation: () -> Unit): FencedOperationResult =
        executeWithConnection(resource, fencingToken) { operation() }
}
