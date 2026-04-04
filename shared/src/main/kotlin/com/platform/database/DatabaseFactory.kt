package com.platform.database

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

/**
 * Database configuration and initialization.
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
        runMigrations(jdbcUrl, username, password)

        Database.connect(
            url = jdbcUrl,
            driver = "org.postgresql.Driver",
            user = username,
            password = password,
        )
    }

    private fun runMigrations(
        jdbcUrl: String,
        username: String,
        password: String,
    ) {
        Flyway
            .configure()
            .dataSource(jdbcUrl, username, password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}
