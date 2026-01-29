package com.platform.database
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Base class for database integration tests.
 *
 * ## How it works
 * This class sets up a PostgreSQL database connection that is shared across all tests.
 * The repositories use Exposed's `transaction {}` which operates on the global/default
 * database connection established by `DatabaseFactory.init()`.
 *
 * ## Test Isolation
 * All tests share a single PostgreSQL instance. To prevent interference:
 * - `@BeforeEach cleanTables()` truncates all tables before each test
 * - Gradle is configured with `maxParallelForks = 1` to run tests sequentially
 * - This ensures each test starts with a clean, empty database
 *
 * ## Migrations
 * Flyway migrations are run once when the database is initialized. Between tests,
 * only data is cleared (not schema), which is faster than dropping/recreating tables.
 *
 * ## CI vs Local Development
 *
 * **In CI (GitHub Actions):** Testcontainers works out of the box with standard Docker.
 *
 * **Local Development (macOS):** Use Colima instead of Docker Desktop for reliable
 * Testcontainers support. The build.gradle.kts is configured to automatically detect
 * and use Colima's Docker socket when available:
 *
 * ```bash
 * brew install colima docker
 * colima start
 * ./gradlew test
 * ```
 *
 * Environment variables (TEST_DATABASE_URL, TEST_DATABASE_USER, TEST_DATABASE_PASSWORD)
 * are still supported as a fallback if you prefer to manage the database manually.
 */
abstract class DatabaseTestBase {
    companion object {
        private var postgres: PostgreSQLContainer<*>? = null
        private var initialized = false

        @BeforeAll
        @JvmStatic
        fun setupDatabase() {
            if (initialized) return
            initialized = true

            val externalUrl = System.getenv("TEST_DATABASE_URL")
            if (externalUrl != null) {
                val username = System.getenv("TEST_DATABASE_USER") ?: "postgres"
                val password = System.getenv("TEST_DATABASE_PASSWORD") ?: "postgres"

                // Clean and migrate for external database
                cleanAndMigrate(externalUrl, username, password)

                DatabaseFactory.init(
                    jdbcUrl = externalUrl,
                    username = username,
                    password = password,
                )
            } else {
                // Use testcontainers (works in CI)
                postgres =
                    PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply {
                        withDatabaseName("platform_test")
                        withUsername("test")
                        withPassword("test")
                        start()
                    }

                DatabaseFactory.init(
                    jdbcUrl = postgres!!.jdbcUrl,
                    username = postgres!!.username,
                    password = postgres!!.password,
                )
            }
        }

        private fun cleanAndMigrate(
            jdbcUrl: String,
            username: String,
            password: String,
        ) {
            Flyway
                .configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .also { it.clean() }
                .migrate()
        }
    }

    /**
     * Resets the database to a clean state before each test.
     * Truncates all tables in reverse dependency order (Services before Organizations)
     * to respect foreign key constraints.
     */
    @BeforeEach
    fun cleanTables() {
        runBlocking {
            newSuspendedTransaction {
                Services.deleteAll()
                Organizations.deleteAll()
            }
        }
    }
}
