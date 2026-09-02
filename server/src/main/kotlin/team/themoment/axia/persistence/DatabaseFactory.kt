package team.themoment.axia.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException
import javax.sql.DataSource

object DatabaseFactory {
    private const val MYSQL_DRIVER_CLASS_NAME = "com.mysql.cj.jdbc.Driver"
    private const val DEFAULT_SCHEMA_RESOURCE = "/schema.sql"

    fun create(config: DatabaseConfig): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                username = config.username
                password = config.password
                driverClassName = MYSQL_DRIVER_CLASS_NAME
                poolName = config.poolName
                maximumPoolSize = config.maximumPoolSize
                minimumIdle = config.minimumIdle
                connectionTimeout = config.connectionTimeoutMillis
                validationTimeout = config.validationTimeoutMillis
                idleTimeout = config.idleTimeoutMillis
                maxLifetime = config.maxLifetimeMillis
                isAutoCommit = true
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
                connectionInitSql = "SET time_zone = '+00:00'"

                addDataSourceProperty("cachePrepStmts", "true")
                addDataSourceProperty("prepStmtCacheSize", "250")
                addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
                addDataSourceProperty("useServerPrepStmts", "true")
                addDataSourceProperty("rewriteBatchedStatements", "true")
                addDataSourceProperty("connectionTimeZone", "+00:00")
                addDataSourceProperty("forceConnectionTimeZoneToSession", "true")
                addDataSourceProperty("preserveInstants", "true")
            },
        )

    fun createAndInitialize(config: DatabaseConfig): HikariDataSource {
        val dataSource = create(config)
        try {
            initializeSchema(dataSource)
            return dataSource
        } catch (failure: Throwable) {
            dataSource.close()
            throw failure
        }
    }

    fun initializeSchema(
        dataSource: DataSource,
        resourcePath: String = DEFAULT_SCHEMA_RESOURCE,
    ) {
        require(resourcePath.startsWith('/')) { "Schema resource path must be absolute" }

        val script =
            DatabaseFactory::class.java.getResourceAsStream(resourcePath)?.bufferedReader()?.use { reader ->
                reader.readText()
            } ?: error("Schema resource not found: $resourcePath")

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                schemaStatements(script).forEachIndexed { index, sql ->
                    try {
                        statement.execute(sql)
                    } catch (failure: SQLException) {
                        throw SQLException(
                            "Failed to initialize database schema at statement ${index + 1}",
                            failure.sqlState,
                            failure.errorCode,
                            failure,
                        )
                    }
                }
            }
        }
    }

    private fun schemaStatements(script: String): List<String> =
        script
            .lineSequence()
            .filterNot { line -> line.trimStart().startsWith("--") }
            .joinToString(separator = "\n")
            .split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
}
