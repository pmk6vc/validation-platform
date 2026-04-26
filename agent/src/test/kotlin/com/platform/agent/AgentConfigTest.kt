package com.platform.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AgentConfigTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Nested
    inner class StaticConfigTests {
        private val fullEnv =
            mapOf(
                "KUBESHARK_URL" to "http://kubeshark:9090",
                "PLATFORM_URL" to "https://platform.example.com",
                "API_KEY" to "vp_key_test123",
            )

        @Test
        fun `fromEnvironment parses all env vars`() {
            val config = StaticConfig.fromEnvironment(fullEnv::get)

            assertEquals("http://kubeshark:9090", config.kubesharkUrl)
            assertEquals("https://platform.example.com", config.platformUrl)
            // COLLECTOR_URL not set — defaults to PLATFORM_URL
            assertEquals("https://platform.example.com", config.collectorUrl)
            assertEquals("vp_key_test123", config.apiKey)
        }

        @Test
        fun `fromEnvironment uses COLLECTOR_URL when set`() {
            val env = fullEnv + ("COLLECTOR_URL" to "https://collector.example.com")
            val config = StaticConfig.fromEnvironment(env::get)

            assertEquals("https://platform.example.com", config.platformUrl)
            assertEquals("https://collector.example.com", config.collectorUrl)
        }

        @Test
        fun `fromEnvironment uses default kubeshark URL when not set`() {
            val env = fullEnv - "KUBESHARK_URL"

            val config = StaticConfig.fromEnvironment(env::get)

            assertEquals("http://kubeshark-front.default:80", config.kubesharkUrl)
        }

        @Test
        fun `fromEnvironment throws when PLATFORM_URL is missing`() {
            val env = fullEnv - "PLATFORM_URL"

            val exception =
                assertThrows<IllegalStateException> {
                    StaticConfig.fromEnvironment(env::get)
                }
            assertEquals(
                "Required environment variable PLATFORM_URL is not set",
                exception.message,
            )
        }

        @Test
        fun `fromEnvironment throws when API_KEY is missing`() {
            val env = fullEnv - "API_KEY"

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
            assertEquals(5.seconds, config.captureInterval)
            assertEquals(30.seconds, config.configPollInterval)
            assertEquals(60.seconds, config.discoveryInterval)
            assertTrue(config.namespaceFilters.isEmpty())
        }

        @Test
        fun `serialization roundtrip preserves all fields`() {
            val config =
                DynamicConfig(
                    targetServices = mapOf("order-service" to "svc-123", "api-gateway" to "svc-456"),
                    samplingRate = 0.25,
                    batchSize = 50,
                    captureInterval = 3.seconds,
                    configPollInterval = 15.seconds,
                    discoveryInterval = 120.seconds,
                    namespaceFilters = listOf("production", "staging"),
                )

            val serialized = json.encodeToString(DynamicConfig.serializer(), config)
            val deserialized = json.decodeFromString<DynamicConfig>(serialized)

            assertEquals(config, deserialized)
        }

        @Test
        fun `durations serialize as Long milliseconds on the wire`() {
            val config =
                DynamicConfig(
                    captureInterval = 2500.milliseconds,
                    configPollInterval = 7.seconds,
                    discoveryInterval = 90.seconds,
                )

            val serialized = json.encodeToString(DynamicConfig.serializer(), config)
            val parsed = json.parseToJsonElement(serialized) as JsonObject

            // Wire format must be a plain integer (number of ms), not an ISO-8601 string
            assertEquals(2500L, (parsed["captureInterval"] as JsonPrimitive).long)
            assertEquals(7000L, (parsed["configPollInterval"] as JsonPrimitive).long)
            assertEquals(90_000L, (parsed["discoveryInterval"] as JsonPrimitive).long)
        }

        @Test
        fun `deserialization accepts Long milliseconds for duration fields`() {
            val json = """{"captureInterval": 2500, "configPollInterval": 7000}"""

            val config = this@AgentConfigTest.json.decodeFromString<DynamicConfig>(json)

            assertEquals(2500.milliseconds, config.captureInterval)
            assertEquals(7.seconds, config.configPollInterval)
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
