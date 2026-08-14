package io.github.dornol.idkit.jdbc

import io.github.dornol.idkit.worker.FencingTokenValidator
import java.sql.SQLException
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
        return acceptWithConflictRetry {
            acceptOnce(resource, fencingToken)
        }
    }

    private fun acceptOnce(resource: String, fencingToken: Long): Boolean {
        if (dialect === JdbcLeaseDialect.MYSQL || dialect === JdbcLeaseDialect.MARIADB) {
            return acceptMySql(resource, fencingToken)
        }
        dataSource.connection.use { connection ->
            connection.prepareStatement(dialect.acceptValidatorTokenSql(tableName)).use { statement ->
                statement.setString(1, resource)
                statement.setLong(2, fencingToken)
                // The dialect-specific upsert performs the compare-and-set atomically.
                if (statement.executeUpdate() == 0) return false
                return true
            }
        }
    }

    private fun acceptWithConflictRetry(operation: () -> Boolean): Boolean {
        var lastFailure: SQLException? = null
        repeat(MAX_CONFLICT_RETRIES) { attempt ->
            try {
                return operation()
            } catch (failure: SQLException) {
                if (!isRetryableConflict(failure) || attempt == MAX_CONFLICT_RETRIES - 1) throw failure
                lastFailure = failure
                Thread.sleep(5L * (attempt + 1))
            }
        }
        throw lastFailure ?: IllegalStateException("fencing token operation did not complete")
    }

    private fun isRetryableConflict(failure: SQLException): Boolean {
        val state = failure.sqlState.orEmpty()
        return state.startsWith("23") || state.startsWith("40") || failure.errorCode in RETRYABLE_VENDOR_CODES
    }

    /**
     * MySQL-compatible drivers do not agree on affected-row counts for a no-op duplicate-key
     * update.  Use insert-ignore followed by a locked conditional update so equal tokens are
     * rejected exactly once even under concurrent first-use calls.
     */
    private fun acceptMySql(resource: String, fencingToken: Long): Boolean =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val inserted = connection.prepareStatement(
                    "INSERT IGNORE INTO $tableName (resource_key, fencing_token) VALUES (?, ?)",
                ).use { statement ->
                    statement.setString(1, resource)
                    statement.setLong(2, fencingToken)
                    statement.executeUpdate()
                }
                if (inserted > 0) {
                    connection.commit()
                    return@use true
                }

                val current = connection.prepareStatement(
                    "SELECT fencing_token FROM $tableName WHERE resource_key = ? FOR UPDATE",
                ).use { statement ->
                    statement.setString(1, resource)
                    statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
                }
                if (current == null || fencingToken <= current) {
                    connection.rollback()
                    return@use false
                }
                val advanced = connection.prepareStatement(
                    "UPDATE $tableName SET fencing_token = ? WHERE resource_key = ? AND fencing_token < ?",
                ).use { statement ->
                    statement.setLong(1, fencingToken)
                    statement.setString(2, resource)
                    statement.setLong(3, fencingToken)
                    statement.executeUpdate()
                }
                if (advanced == 1) {
                    connection.commit()
                    true
                } else {
                    connection.rollback()
                    false
                }
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            }
        }

    private fun requireResource(resource: String) { require(resource.isNotBlank()) { "resource must not be blank" } }
    private fun requireIdentifier(value: String) { requireSimpleSqlIdentifier(value) }

    private companion object {
        private const val MAX_CONFLICT_RETRIES = 8
        private val RETRYABLE_VENDOR_CODES = setOf(1, 60, 1205, 1213)
    }
}
