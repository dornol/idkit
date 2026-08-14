package io.github.dornol.idkit.jdbc

import com.microsoft.sqlserver.jdbc.SQLServerDataSource
import com.mysql.cj.jdbc.MysqlDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mariadb.jdbc.MariaDbDataSource
import oracle.jdbc.pool.OracleDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.Executors
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** Smoke-tests the SQL that is not covered by the PostgreSQL integration suite. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcAdditionalDialectIntegrationTest {
    private val containers = mutableListOf<GenericContainer<*>>()
    private val scheduler = Executors.newScheduledThreadPool(2)

    @BeforeAll
    fun verifyDocker() {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker is unavailable; skipping JDBC dialect integration tests",
        )
    }

    @AfterAll
    fun stopContainers() {
        scheduler.shutdownNow()
        containers.asReversed().forEach { it.stop() }
    }

    @Test
    fun `MySQL dialect acquires and releases a lease`() {
        val database = start(
            GenericContainer(DockerImageName.parse("mysql:8.4"))
                .withEnv("MYSQL_DATABASE", "idkit")
                .withEnv("MYSQL_USER", "idkit")
                .withEnv("MYSQL_PASSWORD", "idkit")
                .withEnv("MYSQL_ROOT_PASSWORD", "root")
                .withExposedPorts(3306)
                .waitingFor(Wait.forLogMessage(".*ready for connections.*", 1)),
        )
        val dataSource = MysqlDataSource().apply {
            setURL("jdbc:mysql://${database.host}:${database.getMappedPort(3306)}/idkit")
            user = "idkit"
            password = "idkit"
        }
        exercise(dataSource, JdbcLeaseDialect.MYSQL, "mysql")
    }

    @Test
    fun `MariaDB dialect acquires and releases a lease`() {
        val database = start(
            GenericContainer(DockerImageName.parse("mariadb:11.8"))
                .withEnv("MARIADB_DATABASE", "idkit")
                .withEnv("MARIADB_USER", "idkit")
                .withEnv("MARIADB_PASSWORD", "idkit")
                .withEnv("MARIADB_ROOT_PASSWORD", "root")
                .withExposedPorts(3306)
                .waitingFor(Wait.forLogMessage(".*ready for connections.*", 1)),
        )
        val dataSource = MariaDbDataSource().apply {
            setUrl("jdbc:mariadb://${database.host}:${database.getMappedPort(3306)}/idkit")
            setUser("idkit")
            setPassword("idkit")
        }
        exercise(dataSource, JdbcLeaseDialect.MARIADB, "mariadb")
    }

    @Test
    fun `MSSQL dialect acquires and releases a lease`() {
        val database = start(
            GenericContainer(DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"))
                .withEnv("ACCEPT_EULA", "Y")
                .withEnv("MSSQL_SA_PASSWORD", "IdkitTest_1234!")
                .withEnv("MSSQL_PID", "Developer")
                .withExposedPorts(1433)
                .waitingFor(Wait.forLogMessage(".*SQL Server is now ready for client connections.*", 1)),
        )
        val dataSource = SQLServerDataSource().apply {
            serverName = database.host
            portNumber = database.getMappedPort(1433)
            databaseName = "master"
            setUser("sa")
            setPassword("IdkitTest_1234!")
            trustServerCertificate = true
        }
        exercise(dataSource, JdbcLeaseDialect.MSSQL, "mssql")
    }

    @Test
    fun `Oracle dialect acquires and releases a lease`() {
        val database = start(
            GenericContainer(DockerImageName.parse("gvenzl/oracle-xe:21-slim-faststart"))
                .withEnv("ORACLE_PASSWORD", "IdkitTest_1234!")
                .withExposedPorts(1521)
                .waitingFor(Wait.forLogMessage(".*DATABASE IS READY TO USE.*", 1)),
        )
        val dataSource = OracleDataSource().apply {
            url = "jdbc:oracle:thin:@${database.host}:${database.getMappedPort(1521)}/XEPDB1"
            setUser("system")
            setPassword("IdkitTest_1234!")
        }
        exercise(dataSource, JdbcLeaseDialect.ORACLE, "oracle")
    }

    private fun exercise(dataSource: DataSource, dialect: JdbcLeaseDialect, suffix: String) {
        awaitConnection(dataSource)
        val store = JdbcWorkerIdLeaseStore(
            dataSource = dataSource,
            scheduler = scheduler,
            dialect = dialect,
            tableName = "idkit_${suffix}_lease",
        )
        val first = store.acquireAny(workerCount = 2, owner = "integration", ttlMillis = 30_000)
        assertNotNull(first)
        assertNull(store.tryAcquire(first.workerId, first.datacenterId, "conflict", 30_000))
        first.close()
        assertNotNull(store.tryAcquire(first.workerId, first.datacenterId, "reacquire", 30_000)?.also { it.close() })

        val validator = JdbcFencingTokenValidator(
            dataSource = dataSource,
            dialect = dialect,
            tableName = "idkit_${suffix}_fencing",
        )
        validator.initialize()
        assertTrue(validator.accept("integration-resource", 10))
        assertFalse(validator.accept("integration-resource", 9))
        assertEquals(10L, validator.current("integration-resource"))

        val workers = Executors.newFixedThreadPool(4)
        try {
            val accepted = (1..16).map {
                workers.submit(Callable { validator.accept("contention-resource", 20) })
            }.count { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, accepted)
        } finally {
            workers.shutdownNow()
        }
    }

    private fun awaitConnection(dataSource: DataSource) {
        var lastFailure: Exception? = null
        repeat(60) {
            try {
                dataSource.connection.use { connection ->
                    if (connection.isValid(1)) return
                }
            } catch (failure: Exception) {
                lastFailure = failure
            }
            TimeUnit.SECONDS.sleep(1)
        }
        throw IllegalStateException("Database did not become ready", lastFailure)
    }

    private fun start(container: GenericContainer<*>): GenericContainer<*> {
        containers += container
        return container.apply { start() }
    }
}
