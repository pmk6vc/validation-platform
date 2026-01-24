package com.platform.database

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
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
 * - `@BeforeEach cleanTables()` drops and recreates all tables before each test
 * - Gradle is configured with `maxParallelForks = 1` to run tests sequentially
 * - This ensures each test starts with a clean, empty database
 *
 * ## CI vs Local Development
 *
 * **In CI (GitHub Actions):** Testcontainers works out of the box. No environment variables
 * needed - it will automatically spin up a PostgreSQL container.
 *
 * **Local Development:** Testcontainers may fail to connect to Docker Desktop on macOS due to
 * a known compatibility issue between the docker-java library and certain Docker Desktop
 * configurations (returns HTTP 400 with empty response). As a workaround, you can:
 *
 * 1. Use the helper script: `./scripts/test-local.sh`
 * 2. Or manually set environment variables:
 *    - TEST_DATABASE_URL: JDBC URL (e.g., jdbc:postgresql://localhost:5433/platform_test)
 *    - TEST_DATABASE_USER: Database username
 *    - TEST_DATABASE_PASSWORD: Database password
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
                // Use external database (for local dev when Testcontainers doesn't work)
                DatabaseFactory.init(
                    jdbcUrl = externalUrl,
                    username = System.getenv("TEST_DATABASE_USER") ?: "postgres",
                    password = System.getenv("TEST_DATABASE_PASSWORD") ?: "postgres"
                )
            } else {
                // Use testcontainers (works in CI)
                postgres = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply {
                    withDatabaseName("platform_test")
                    withUsername("test")
                    withPassword("test")
                    start()
                }

                DatabaseFactory.init(
                    jdbcUrl = postgres!!.jdbcUrl,
                    username = postgres!!.username,
                    password = postgres!!.password
                )
            }
        }
    }

    /**
     * Resets the database to a clean state before each test.
     * Drops and recreates all tables to ensure complete isolation between tests.
     * Tables are dropped in reverse dependency order (Services before Organizations)
     * to respect foreign key constraints.
     */
    @BeforeEach
    fun cleanTables() {
        transaction {
            SchemaUtils.drop(Services, Organizations)
            SchemaUtils.create(Organizations, Services)
        }
    }
}
