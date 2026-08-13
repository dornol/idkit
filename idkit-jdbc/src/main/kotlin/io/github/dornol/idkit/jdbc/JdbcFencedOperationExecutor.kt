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
                val current = connection.prepareStatement("SELECT fencing_token FROM $tableName WHERE resource_key = ?").use { statement ->
                    statement.setString(1, resource)
                    statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
                }
                if (current != null && fencingToken <= current) {
                    connection.rollback()
                    return FencedOperationResult.REJECTED_STALE
                }
                val applied = connection.prepareStatement(dialect.fencedOperationSql(tableName)).use { statement ->
                    statement.setString(1, resource)
                    statement.setLong(2, fencingToken)
                    statement.executeUpdate()
                }
                if (applied == 0 && current != null) {
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

    override fun execute(resource: String, fencingToken: Long, operation: () -> Unit): FencedOperationResult =
        executeWithConnection(resource, fencingToken) { operation() }
}
