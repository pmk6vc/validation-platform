package com.platform.shared.database

import com.platform.shared.secrets.SecretsProvider
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.api.exception.FlywayValidateException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseFactoryTest {
    companion object {
        // Single Postgres container shared across MigrationModeTests; each test
        // creates its own database within it so they don't see each other's
        // migration state. Stopping is left to TestContainers' Ryuk daemon.
        private lateinit var postgres: PostgreSQLContainer
        private lateinit var adminDataSource: HikariDataSource
        private lateinit var baseJdbcUrl: String

        @BeforeAll
        @JvmStatic
        fun startPostgres() {
            postgres =
                PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply {
                    withDatabaseName("admin")
                    withUsername("test")
                    withPassword("test")
                    start()
                }
            // Strip the database segment so we can append per-test database names.
            // Container URL: jdbc:postgresql://host:port/admin?... → keep up to the host:port.
            val jdbcUrl = postgres.jdbcUrl
            val dbStart = jdbcUrl.indexOf("/", "jdbc:postgresql://".length)
            baseJdbcUrl = jdbcUrl.substring(0, dbStart)
            adminDataSource =
                HikariDataSource(
                    HikariConfig().apply {
                        this.jdbcUrl = jdbcUrl
                        username = postgres.username
                        password = postgres.password
                        driverClassName = "org.postgresql.Driver"
                        maximumPoolSize = 1
                    },
                )
        }

        @AfterAll
        @JvmStatic
        fun closeAdmin() {
            if (::adminDataSource.isInitialized) adminDataSource.close()
        }

        fun tableExists(
            ds: DataSource,
            name: String,
        ): Boolean =
            ds.connection.use { conn ->
                conn.metaData.getTables(null, null, name, arrayOf("TABLE")).use { rs -> rs.next() }
            }
    }

    private class RecordingSecretsProvider(
        private val values: Map<String, String> = emptyMap(),
    ) : SecretsProvider {
        val requestedKeys = mutableListOf<String>()

        override fun get(name: String): String {
            requestedKeys += name
            return values[name] ?: error("RecordingSecretsProvider: no value for '$name'")
        }
    }

    private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    @Test
    fun `defaults to localhost url and postgres user when env unset`() {
        val secrets = RecordingSecretsProvider(mapOf("DATABASE_PASSWORD" to "secret"))

        val resolved = DatabaseFactory.resolveConfig(envOf(), secrets)

        assertEquals("jdbc:postgresql://localhost:5432/platform", resolved.jdbcUrl)
        assertEquals("postgres", resolved.username)
        assertEquals("secret", resolved.password)
        assertEquals(listOf("DATABASE_PASSWORD"), secrets.requestedKeys)
    }

    @Test
    fun `password mode reads DATABASE_PASSWORD via secrets provider`() {
        val secrets = RecordingSecretsProvider(mapOf("DATABASE_PASSWORD" to "from-secrets"))

        val resolved =
            DatabaseFactory.resolveConfig(
                envOf("DATABASE_AUTH_MODE" to "password"),
                secrets,
            )

        assertEquals("from-secrets", resolved.password)
        assertEquals(listOf("DATABASE_PASSWORD"), secrets.requestedKeys)
    }

    @Test
    fun `iam mode does not consult secrets provider and yields empty password`() {
        val secrets = RecordingSecretsProvider()

        val resolved =
            DatabaseFactory.resolveConfig(
                envOf("DATABASE_AUTH_MODE" to "iam"),
                secrets,
            )

        assertEquals("", resolved.password)
        assertTrue(secrets.requestedKeys.isEmpty(), "IAM mode must not call SecretsProvider")
    }

    @Test
    fun `auth mode is case insensitive`() {
        val secrets = RecordingSecretsProvider()

        val resolved =
            DatabaseFactory.resolveConfig(
                envOf("DATABASE_AUTH_MODE" to "IAM"),
                secrets,
            )

        assertEquals("", resolved.password)
    }

    @Test
    fun `unknown auth mode throws with descriptive message`() {
        val secrets = RecordingSecretsProvider()

        val ex =
            assertThrows<IllegalStateException> {
                DatabaseFactory.resolveConfig(
                    envOf("DATABASE_AUTH_MODE" to "kerberos"),
                    secrets,
                )
            }

        assertTrue(ex.message!!.contains("kerberos"), "error message should include the bad value")
        assertTrue(secrets.requestedKeys.isEmpty(), "unknown mode must not consult SecretsProvider")
    }

    @Test
    fun `custom DATABASE_URL and DATABASE_USER flow through unchanged`() {
        val secrets = RecordingSecretsProvider(mapOf("DATABASE_PASSWORD" to "pw"))

        val resolved =
            DatabaseFactory.resolveConfig(
                envOf(
                    "DATABASE_URL" to "jdbc:postgresql://db.example.com:6543/myapp",
                    "DATABASE_USER" to "app_user",
                ),
                secrets,
            )

        assertEquals("jdbc:postgresql://db.example.com:6543/myapp", resolved.jdbcUrl)
        assertEquals("app_user", resolved.username)
        assertEquals("pw", resolved.password)
    }

    /**
     * Migration mode tests against a real Postgres TestContainer. Each test
     * carves out a fresh Postgres database on the shared container so it can
     * exercise applyMigrations in isolation without colliding with other tests.
     */
    @Nested
    inner class MigrationModeTests {
        private fun freshDatabase(name: String): HikariDataSource {
            // CREATE DATABASE on the shared container, then return a DataSource scoped to it.
            adminDataSource.connection.use { conn: Connection ->
                conn.createStatement().use { stmt -> stmt.execute("CREATE DATABASE \"$name\"") }
            }
            return HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = "$baseJdbcUrl/$name"
                    username = postgres.username
                    password = postgres.password
                    driverClassName = "org.postgresql.Driver"
                    maximumPoolSize = 2
                },
            )
        }

        @Test
        fun `MIGRATE applies migrations and creates expected tables`() {
            val ds = freshDatabase("migrate_${System.nanoTime()}")
            ds.use {
                DatabaseFactory.applyMigrations(it, MigrationMode.MIGRATE)
                assertTrue(tableExists(it, "organizations"))
                assertTrue(tableExists(it, "captured_inputs"))
                assertTrue(tableExists(it, "flyway_schema_history"))
            }
        }

        @Test
        fun `VALIDATE succeeds after MIGRATE has run`() {
            val ds = freshDatabase("validate_ok_${System.nanoTime()}")
            ds.use {
                DatabaseFactory.applyMigrations(it, MigrationMode.MIGRATE)
                // Should not throw — schema matches the migration chain on the classpath.
                DatabaseFactory.applyMigrations(it, MigrationMode.VALIDATE)
            }
        }

        @Test
        fun `VALIDATE throws on a fresh empty database`() {
            val ds = freshDatabase("validate_empty_${System.nanoTime()}")
            ds.use {
                // No migrations have run — Flyway's validate() must reject the empty schema
                // so the follower service fails its startup probe and gets retried.
                assertThrows<FlywayValidateException> {
                    DatabaseFactory.applyMigrations(it, MigrationMode.VALIDATE)
                }
            }
        }
    }

    @Test
    fun `iam mode honors custom URL and user but ignores DATABASE_PASSWORD`() {
        val cloudSqlUrl =
            "jdbc:postgresql:///validation?cloudSqlInstance=p:r:i&socketFactory=com.google.cloud.sql.postgres.SocketFactory&enableIamAuth=true"
        val secrets = RecordingSecretsProvider(mapOf("DATABASE_PASSWORD" to "should-not-be-read"))

        val resolved =
            DatabaseFactory.resolveConfig(
                envOf(
                    "DATABASE_URL" to cloudSqlUrl,
                    "DATABASE_USER" to "platform-sa@project.iam.gserviceaccount.com",
                    "DATABASE_AUTH_MODE" to "iam",
                ),
                secrets,
            )

        assertEquals(cloudSqlUrl, resolved.jdbcUrl)
        assertEquals("platform-sa@project.iam.gserviceaccount.com", resolved.username)
        assertEquals("", resolved.password)
        assertTrue(secrets.requestedKeys.isEmpty())
    }
}
