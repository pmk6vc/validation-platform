package com.platform.shared.secrets

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class EnvSecretsProviderTest {
    private val provider = EnvSecretsProvider()

    @Test
    fun `should return value when env var is set`() {
        // The PATH env var is reliably present on all platforms.
        val result = provider.get("PATH")

        assertEquals(System.getenv("PATH"), result)
    }

    @Test
    fun `should throw when env var is missing`() {
        val missingVar = "SECRETS_PROVIDER_TEST_VAR_THAT_DOES_NOT_EXIST_12345"

        val exception =
            assertThrows<IllegalStateException> {
                provider.get(missingVar)
            }

        assert(exception.message!!.contains(missingVar)) {
            "Error message should mention the missing variable name"
        }
    }
}
