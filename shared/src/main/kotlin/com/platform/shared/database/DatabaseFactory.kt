package com.platform.shared.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

/**
 * Database configuration and initialization.
 *
 * Creates a HikariCP connection pool and passes it to both Flyway and Exposed.
 * Pool size and connection timeout are configurable via env vars with sensible defaults.
 */
object DatabaseFactory {
    fun initFromEnvironment() {
        val jdbcUrl =
            System.getenv("DATABASE_URL")
                ?: "jdbc:postgresql://localhost:5432/platform"
        val username = System.getenv("DATABASE_USER") ?: "postgres"
        val password = System.getenv("DATABASE_PASSWORD") ?: "postgres"
        init(jdbcUrl, username, password)
    }

    fun init(
        jdbcUrl: String,
        username: String,
        password: String,
    ) {
        val poolSize = System.getenv("DATABASE_POOL_SIZE")?.toIntOrNull() ?: 10
        val connectionTimeoutMs = System.getenv("DATABASE_CONNECTION_TIMEOUT_MS")?.toLongOrNull() ?: 30_000L

        val dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    this.jdbcUrl = jdbcUrl
                    this.username = username
                    this.password = password
                    this.maximumPoolSize = poolSize
                    this.connectionTimeout = connectionTimeoutMs
                    this.driverClassName = "org.postgresql.Driver"
                },
            )

        runMigrations(dataSource)
        Database.connect(dataSource)
    }

    private fun runMigrations(dataSource: HikariDataSource) {
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}
