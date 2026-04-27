package com.platform.shared.database

import com.platform.shared.secrets.SecretsProvider
import com.platform.shared.secrets.secretsProviderFromEnvironment
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
    /**
     * Initializes the database using values from the environment.
     *
     * - `DATABASE_URL`: JDBC URL (defaults to local Postgres).
     * - `DATABASE_USER`: Postgres user (defaults to `postgres`).
     * - `DATABASE_AUTH_MODE`: `password` (default) or `iam`.
     *   - `password`: read `DATABASE_PASSWORD` via [secretsProvider]. Local /
     *     docker / minikube use this with literal env var values.
     *   - `iam`: no password — the cloud-sql-jdbc-socket-factory authenticates
     *     as the service account via Workload Identity (Cloud Run only). The
     *     URL must include `enableIamAuth=true` and the user must be the SA
     *     email of a Postgres user typed `CLOUD_IAM_SERVICE_ACCOUNT`.
     */
    fun initFromEnvironment(secretsProvider: SecretsProvider = secretsProviderFromEnvironment()) {
        val jdbcUrl =
            System.getenv("DATABASE_URL")
                ?: "jdbc:postgresql://localhost:5432/platform"
        val username = System.getenv("DATABASE_USER") ?: "postgres"
        val authMode = (System.getenv("DATABASE_AUTH_MODE") ?: "password").lowercase()
        val password =
            when (authMode) {
                "password" -> secretsProvider.get("DATABASE_PASSWORD")
                "iam" -> "" // SocketFactory provides the OAuth token in place of a password
                else -> error("Unknown DATABASE_AUTH_MODE: '$authMode' (expected 'password' or 'iam')")
            }
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
                    // IAM mode passes "" — Hikari accepts that and the
                    // socket factory provides the OAuth token.
                    if (password.isNotEmpty()) this.password = password
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
