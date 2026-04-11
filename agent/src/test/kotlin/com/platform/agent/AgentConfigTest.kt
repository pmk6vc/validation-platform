package com.platform.agent

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentConfigTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Nested
    inner class StaticConfigTests {
        @Test
        fun `fromEnvironment parses all env vars`() {
            val env =
                mapOf(
                    "KUBESHARK_URL" to "http://kubeshark:9090",
                    "COLLECTOR_URL" to "https://collector.example.com",
                    "API_KEY" to "vp_key_test123",
                )

            val config = StaticConfig.fromEnvironment(env::get)

            assertEquals("http://kubeshark:9090", config.kubesharkUrl)
            assertEquals("https://collector.example.com", config.collectorUrl)
            assertEquals("vp_key_test123", config.apiKey)
        }

        @Test
        fun `fromEnvironment uses default kubeshark URL when not set`() {
            val env =
                mapOf(
                    "COLLECTOR_URL" to "https://collector.example.com",
                    "API_KEY" to "vp_key_test123",
                )

            val config = StaticConfig.fromEnvironment(env::get)

            assertEquals("http://kubeshark-front.default:80", config.kubesharkUrl)
        }

        @Test
        fun `fromEnvironment throws when COLLECTOR_URL is missing`() {
            val env = mapOf("API_KEY" to "vp_key_test123")

            val exception =
                assertThrows<IllegalStateException> {
                    StaticConfig.fromEnvironment(env::get)
                }
            assertEquals(
                "Required environment variable COLLECTOR_URL is not set",
                exception.message,
            )
        }

        @Test
        fun `fromEnvironment throws when API_KEY is missing`() {
            val env = mapOf("COLLECTOR_URL" to "https://collector.example.com")

            val exception =
                assertThrows<IllegalStateException> {
                    StaticConfig.fromEnvironment(env::get)
                }
            assertEquals(
                "Required environment variable API_KEY is not set",
                exception.message,
            )
        }
    }

    @Nested
    inner class DynamicConfigTests {
        @Test
        fun `default config has sensible values`() {
            val config = DynamicConfig.default()

            assertTrue(config.targetServices.isEmpty())
            assertEquals(1.0, config.samplingRate)
            assertEquals(100, config.batchSize)
            assertEquals(5000L, config.captureIntervalMs)
            assertEquals(30000L, config.configPollIntervalMs)
            assertEquals(60000L, config.discoveryIntervalMs)
            assertTrue(config.namespaceFilters.isEmpty())
        }

        @Test
        fun `serialization roundtrip preserves all fields`() {
            val config =
                DynamicConfig(
                    targetServices = mapOf("order-service" to "svc-123", "api-gateway" to "svc-456"),
                    samplingRate = 0.25,
                    batchSize = 50,
                    captureIntervalMs = 3000,
                    configPollIntervalMs = 15000,
                    discoveryIntervalMs = 120000,
                    namespaceFilters = listOf("production", "staging"),
                )

            val serialized = json.encodeToString(DynamicConfig.serializer(), config)
            val deserialized = json.decodeFromString<DynamicConfig>(serialized)

            assertEquals(config, deserialized)
        }

        @Test
        fun `deserialization uses defaults for missing fields`() {
            val minimal = """{"targetServices": {"order-service": "svc-123"}}"""

            val config = json.decodeFromString<DynamicConfig>(minimal)

            assertEquals(mapOf("order-service" to "svc-123"), config.targetServices)
            assertEquals(1.0, config.samplingRate)
            assertEquals(100, config.batchSize)
        }

        @Test
        fun `deserialization ignores unknown fields`() {
            val withExtras = """{
                "targetServices": {},
                "samplingRate": 0.5,
                "futureField": "should be ignored"
            }"""

            val config = json.decodeFromString<DynamicConfig>(withExtras)

            assertEquals(0.5, config.samplingRate)
        }

        @Test
        fun `empty JSON object produces default config`() {
            val config = json.decodeFromString<DynamicConfig>("{}")

            assertEquals(DynamicConfig.default(), config)
        }
    }
}
