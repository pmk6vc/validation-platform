package com.platform.shared.database

import com.platform.shared.secrets.SecretsProvider
import com.platform.shared.secrets.secretsProviderFromEnvironment
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

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
    fun initFromEnvironment(
        secretsProvider: SecretsProvider = secretsProviderFromEnvironment(),
        migrationMode: MigrationMode = MigrationMode.MIGRATE,
    ) {
        val resolved = resolveConfig(System::getenv, secretsProvider)
        init(resolved.jdbcUrl, resolved.username, resolved.password, migrationMode)
    }

    internal data class ResolvedDbConfig(
        val jdbcUrl: String,
        val username: String,
        val password: String,
    )

    /**
     * Pure resolution of DB connection inputs from a (typically env-var-backed)
     * lookup function and a [SecretsProvider]. Extracted so it can be tested
     * without spinning up a real DataSource.
     */
    internal fun resolveConfig(
        env: (String) -> String?,
        secretsProvider: SecretsProvider,
    ): ResolvedDbConfig {
        val jdbcUrl = env("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/platform"
        val username = env("DATABASE_USER") ?: "postgres"
        val authMode = (env("DATABASE_AUTH_MODE") ?: "password").lowercase()
        val password =
            when (authMode) {
                "password" -> secretsProvider.get("DATABASE_PASSWORD")
                "iam" -> "" // SocketFactory provides the OAuth token in place of a password
                else -> error("Unknown DATABASE_AUTH_MODE: '$authMode' (expected 'password' or 'iam')")
            }
        return ResolvedDbConfig(jdbcUrl, username, password)
    }

    fun init(
        jdbcUrl: String,
        username: String,
        password: String,
        migrationMode: MigrationMode = MigrationMode.MIGRATE,
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

        applyMigrations(dataSource, migrationMode)
        Database.connect(dataSource)
    }

    /**
     * Apply or validate Flyway migrations against [dataSource]. Extracted
     * (and `internal`) so tests can drive both modes without going through
     * the full HikariCP / env-var setup.
     */
    internal fun applyMigrations(
        dataSource: DataSource,
        mode: MigrationMode,
    ) {
        val flyway =
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
        when (mode) {
            MigrationMode.MIGRATE -> flyway.migrate()
            MigrationMode.VALIDATE -> flyway.validate()
        }
    }
}
