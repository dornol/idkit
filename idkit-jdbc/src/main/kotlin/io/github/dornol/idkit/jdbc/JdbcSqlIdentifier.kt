package io.github.dornol.idkit.jdbc

private val simpleSqlIdentifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

internal fun requireSimpleSqlIdentifier(value: String, name: String = "tableName") {
    require(value.matches(simpleSqlIdentifier)) {
        "$name must be a simple SQL identifier"
    }
}
