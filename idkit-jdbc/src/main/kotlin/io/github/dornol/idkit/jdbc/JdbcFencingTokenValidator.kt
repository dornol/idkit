package io.github.dornol.idkit.jdbc

import io.github.dornol.idkit.worker.FencingTokenValidator
import javax.sql.DataSource

/** Durable fencing validator backed by an atomic, dialect-specific upsert. */
class JdbcFencingTokenValidator(
    private val dataSource: DataSource,
    private val dialect: JdbcLeaseDialect = JdbcLeaseDialect.POSTGRESQL,
    private val tableName: String = "idkit_fencing_token",
) : FencingTokenValidator {
    init { requireIdentifier(tableName) }

    fun initialize() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(dialect.createValidatorTableSql(tableName)) }
        }
    }

    override fun current(resource: String): Long? {
        requireResource(resource)
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT fencing_token FROM $tableName WHERE resource_key = ?").use { statement ->
                statement.setString(1, resource)
                statement.executeQuery().use { result ->
                    if (!result.next()) return null
                    return result.getLong(1)
                }
            }
        }
    }

    override fun accept(resource: String, fencingToken: Long): Boolean {
        requireResource(resource)
        require(fencingToken > 0) { "fencingToken must be > 0" }
        dataSource.connection.use { connection ->
            val previous = connection.prepareStatement("SELECT fencing_token FROM $tableName WHERE resource_key = ?").use { statement ->
                statement.setString(1, resource)
                statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
            }
            if (previous != null && fencingToken <= previous) return false
            connection.prepareStatement(dialect.acceptValidatorTokenSql(tableName)).use { statement ->
                statement.setString(1, resource)
                statement.setLong(2, fencingToken)
                statement.executeUpdate()
                return true
            }
        }
    }

    private fun requireResource(resource: String) { require(resource.isNotBlank()) { "resource must not be blank" } }
    private fun requireIdentifier(value: String) { require(value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "tableName must be a simple SQL identifier" } }
}
