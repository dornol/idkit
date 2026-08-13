package io.github.dornol.idkit.jdbc

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JdbcLeaseDialectTest {
    @Test
    fun `all bundled dialects provide table creation and slot insertion SQL`() {
        val dialects = listOf(
            JdbcLeaseDialect.POSTGRESQL,
            JdbcLeaseDialect.MYSQL,
            JdbcLeaseDialect.MARIADB,
            JdbcLeaseDialect.MSSQL,
            JdbcLeaseDialect.ORACLE,
        )
        dialects.forEach { dialect ->
            assertTrue(dialect.createTableSql.contains("%s"))
            assertTrue(dialect.insertIfAbsentSql("worker_lease").contains("worker_lease"))
            assertTrue(dialect.fromTableSql("worker_lease").contains("worker_lease"))
        }
    }

    @Test
    fun `MSSQL uses update locks in the from clause`() {
        val from = JdbcLeaseDialect.MSSQL.fromTableSql("worker_lease")
        assertTrue(from.contains("UPDLOCK"))
        assertTrue(from.contains("ROWLOCK"))
        assertTrue(from.contains("HOLDLOCK"))
    }
}
