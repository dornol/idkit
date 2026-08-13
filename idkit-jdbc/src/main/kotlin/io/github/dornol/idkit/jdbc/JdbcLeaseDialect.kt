package io.github.dornol.idkit.jdbc

/** SQL fragments that differ between supported relational databases. */
interface JdbcLeaseDialect {
    val createTableSql: String
    fun addFencingTokenSql(table: String): String
    fun isDuplicateFencingColumn(ex: java.sql.SQLException): Boolean = false
    fun createValidatorTableSql(table: String): String
    fun acceptValidatorTokenSql(table: String): String
    fun fencedOperationSql(table: String): String
    fun insertIfAbsentSql(table: String): String
    fun fromTableSql(table: String): String
    val lockSuffix: String

    companion object {
        @JvmField val POSTGRESQL: JdbcLeaseDialect = PostgresDialect
        @JvmField val MYSQL: JdbcLeaseDialect = MySqlDialect
        @JvmField val MARIADB: JdbcLeaseDialect = MySqlDialect
        @JvmField val MSSQL: JdbcLeaseDialect = SqlServerDialect
        @JvmField val ORACLE: JdbcLeaseDialect = OracleDialect
    }
}

private object PostgresDialect : JdbcLeaseDialect {
    override val createTableSql = """
        CREATE TABLE IF NOT EXISTS %s (
          datacenter_id INTEGER NOT NULL,
          worker_id INTEGER NOT NULL,
          owner_token VARCHAR(512),
          lease_until BIGINT,
          fencing_token BIGINT NOT NULL DEFAULT 0,
          PRIMARY KEY (datacenter_id, worker_id)
        )
    """.trimIndent()
    override fun insertIfAbsentSql(table: String) =
        "INSERT INTO $table (datacenter_id, worker_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
    override fun fromTableSql(table: String) = table
    override fun addFencingTokenSql(table: String) =
        "ALTER TABLE $table ADD COLUMN IF NOT EXISTS fencing_token BIGINT NOT NULL DEFAULT 0"
    override val lockSuffix = "FOR UPDATE"
    override fun createValidatorTableSql(table: String) = "CREATE TABLE IF NOT EXISTS $table (resource_key VARCHAR(512) PRIMARY KEY, fencing_token BIGINT NOT NULL)"
    override fun acceptValidatorTokenSql(table: String) = "INSERT INTO $table (resource_key, fencing_token) VALUES (?, ?) ON CONFLICT (resource_key) DO UPDATE SET fencing_token = EXCLUDED.fencing_token WHERE $table.fencing_token < EXCLUDED.fencing_token"
    override fun fencedOperationSql(table: String) = acceptValidatorTokenSql(table)
}

private object MySqlDialect : JdbcLeaseDialect {
    override val createTableSql = """
        CREATE TABLE IF NOT EXISTS %s (
          datacenter_id INT NOT NULL,
          worker_id INT NOT NULL,
          owner_token VARCHAR(512) NULL,
          lease_until BIGINT NULL,
          fencing_token BIGINT NOT NULL DEFAULT 0,
          PRIMARY KEY (datacenter_id, worker_id)
        ) ENGINE=InnoDB
    """.trimIndent()
    override fun insertIfAbsentSql(table: String) =
        "INSERT IGNORE INTO $table (datacenter_id, worker_id) VALUES (?, ?)"
    override fun fromTableSql(table: String) = table
    override fun addFencingTokenSql(table: String) =
        "ALTER TABLE $table ADD COLUMN fencing_token BIGINT NOT NULL DEFAULT 0"
    override fun isDuplicateFencingColumn(ex: java.sql.SQLException) = ex.errorCode == 1060
    override val lockSuffix = "FOR UPDATE"
    override fun createValidatorTableSql(table: String) = "CREATE TABLE IF NOT EXISTS $table (resource_key VARCHAR(512) PRIMARY KEY, fencing_token BIGINT NOT NULL) ENGINE=InnoDB"
    override fun acceptValidatorTokenSql(table: String) = "INSERT INTO $table (resource_key, fencing_token) VALUES (?, ?) ON DUPLICATE KEY UPDATE fencing_token = IF(VALUES(fencing_token) > fencing_token, VALUES(fencing_token), fencing_token)"
    override fun fencedOperationSql(table: String) = acceptValidatorTokenSql(table)
}

private object SqlServerDialect : JdbcLeaseDialect {
    override val createTableSql = """
        IF OBJECT_ID(N'%s', N'U') IS NULL
        BEGIN
          CREATE TABLE %s (
            datacenter_id INT NOT NULL,
            worker_id INT NOT NULL,
            owner_token VARCHAR(512) NULL,
            lease_until BIGINT NULL,
            fencing_token BIGINT NOT NULL DEFAULT 0,
            CONSTRAINT PK_%s PRIMARY KEY (datacenter_id, worker_id)
          )
        END
    """.trimIndent()
    override fun insertIfAbsentSql(table: String) =
        "MERGE $table AS target USING (VALUES (?, ?)) AS source(datacenter_id, worker_id) " +
                "ON target.datacenter_id = source.datacenter_id AND target.worker_id = source.worker_id " +
                "WHEN NOT MATCHED THEN INSERT (datacenter_id, worker_id) VALUES (source.datacenter_id, source.worker_id);"
    override fun fromTableSql(table: String) = "$table WITH (UPDLOCK, ROWLOCK, HOLDLOCK)"
    override fun addFencingTokenSql(table: String) = """
        IF COL_LENGTH(N'$table', N'fencing_token') IS NULL
        BEGIN
          ALTER TABLE $table ADD fencing_token BIGINT NOT NULL CONSTRAINT DF_${table}_fencing DEFAULT 0
        END
    """.trimIndent()
    override val lockSuffix = ""
    override fun createValidatorTableSql(table: String) = "IF OBJECT_ID(N'$table', N'U') IS NULL CREATE TABLE $table (resource_key VARCHAR(512) NOT NULL PRIMARY KEY, fencing_token BIGINT NOT NULL)"
    override fun acceptValidatorTokenSql(table: String) = "MERGE $table AS target USING (VALUES (?, ?)) AS source(resource_key, fencing_token) ON target.resource_key = source.resource_key WHEN MATCHED AND target.fencing_token < source.fencing_token THEN UPDATE SET fencing_token = source.fencing_token WHEN NOT MATCHED THEN INSERT (resource_key, fencing_token) VALUES (source.resource_key, source.fencing_token);"
    override fun fencedOperationSql(table: String) = acceptValidatorTokenSql(table)
}

private object OracleDialect : JdbcLeaseDialect {
    override val createTableSql = """
        BEGIN
          EXECUTE IMMEDIATE 'CREATE TABLE %s (
            datacenter_id NUMBER(10) NOT NULL,
            worker_id NUMBER(10) NOT NULL,
            owner_token VARCHAR2(512),
            lease_until NUMBER(19),
            fencing_token NUMBER(19) DEFAULT 0 NOT NULL,
            CONSTRAINT PK_%s PRIMARY KEY (datacenter_id, worker_id)
          )';
        EXCEPTION
          WHEN OTHERS THEN
            IF SQLCODE != -955 THEN RAISE; END IF;
        END;
    """.trimIndent()
    override fun insertIfAbsentSql(table: String) =
        "MERGE INTO $table target USING (SELECT ? datacenter_id, ? worker_id FROM dual) source " +
                "ON (target.datacenter_id = source.datacenter_id AND target.worker_id = source.worker_id) " +
                "WHEN NOT MATCHED THEN INSERT (datacenter_id, worker_id) VALUES (source.datacenter_id, source.worker_id)"
    override fun fromTableSql(table: String) = table
    override fun addFencingTokenSql(table: String) = """
        BEGIN
          EXECUTE IMMEDIATE 'ALTER TABLE $table ADD (fencing_token NUMBER(19) DEFAULT 0 NOT NULL)';
        EXCEPTION
          WHEN OTHERS THEN
            IF SQLCODE != -1430 THEN RAISE; END IF;
        END;
    """.trimIndent()
    override val lockSuffix = "FOR UPDATE"
    override fun createValidatorTableSql(table: String) = "BEGIN EXECUTE IMMEDIATE 'CREATE TABLE $table (resource_key VARCHAR2(512) PRIMARY KEY, fencing_token NUMBER(19) NOT NULL)'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;"
    override fun acceptValidatorTokenSql(table: String) = "MERGE INTO $table target USING (SELECT ? resource_key, ? fencing_token FROM dual) source ON (target.resource_key = source.resource_key) WHEN MATCHED THEN UPDATE SET target.fencing_token = source.fencing_token WHERE target.fencing_token < source.fencing_token WHEN NOT MATCHED THEN INSERT (resource_key, fencing_token) VALUES (source.resource_key, source.fencing_token)"
    override fun fencedOperationSql(table: String) = acceptValidatorTokenSql(table)
}
