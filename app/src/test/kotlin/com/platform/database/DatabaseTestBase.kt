package com.platform.database

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.postgresql.PostgreSQLContainer
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
 */
abstract class DatabaseTestBase {
    companion object {
        private var postgres: PostgreSQLContainer? = null
        private var initialized = false

        @BeforeAll
        @JvmStatic
        fun setupDatabase() {
            if (initialized) return
            initialized = true

            postgres =
                PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply {
                    withDatabaseName("platform_test")
                    withUsername("test")
                    withPassword("test")
                    start()
                }

            DatabaseFactory.init(
                jdbcUrl = postgres!!.getJdbcUrl(),
                username = postgres!!.getUsername(),
                password = postgres!!.getPassword(),
            )
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
