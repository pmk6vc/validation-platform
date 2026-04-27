package com.platform.shared.database

import com.platform.shared.secrets.SecretsProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseFactoryTest {
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
