package com.platform.agent

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigClientTest {
    private fun mockConfigClient(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "{}",
        onRequest: ((HttpRequestData) -> Unit)? = null,
    ): ConfigClient {
        val engine =
            MockEngine { request ->
                onRequest?.invoke(request)
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        val httpClient = buildAgentPlatformHttpClient(engine)
        return ConfigClient(httpClient, "http://platform:8080", "test-api-key")
    }

    @Test
    fun `returns config on successful response`() =
        runBlocking {
            val body = """{
            "targetServices": {"order-service": "svc-123"},
            "samplingRate": 0.5,
            "batchSize": 200
        }"""

            val config = mockConfigClient(body = body).fetchConfig()

            assertNotNull(config)
            assertEquals(mapOf("order-service" to "svc-123"), config.targetServices)
            assertEquals(0.5, config.samplingRate)
            assertEquals(200, config.batchSize)
        }

    @Test
    fun `returns defaults for missing fields`() =
        runBlocking {
            val config = mockConfigClient(body = "{}").fetchConfig()

            assertNotNull(config)
            assertEquals(DynamicConfig.default(), config)
        }

    @Test
    fun `returns null on non-success status`() =
        runBlocking {
            val config =
                mockConfigClient(
                    status = HttpStatusCode.Unauthorized,
                ).fetchConfig()

            assertNull(config)
        }

    @Test
    fun `returns null on malformed JSON`() =
        runBlocking {
            val config = mockConfigClient(body = "not json").fetchConfig()

            assertNull(config)
        }

    @Test
    fun `returns null on network error`() =
        runBlocking {
            val engine =
                MockEngine {
                    throw IOException("Connection refused")
                }
            val httpClient = buildAgentPlatformHttpClient(engine)
            val client = ConfigClient(httpClient, "http://platform:8080", "key")

            val config = client.fetchConfig()

            assertNull(config)
        }

    @Test
    fun `sends GET to correct endpoint with bearer auth`() =
        runBlocking {
            var capturedUrl = ""
            var capturedAuth = ""

            val client =
                mockConfigClient(onRequest = { request ->
                    capturedUrl = request.url.toString()
                    capturedAuth = request.headers[HttpHeaders.Authorization] ?: ""
                })

            client.fetchConfig()

            assertTrue(capturedUrl.endsWith("/api/agent/config"))
            assertEquals("Bearer test-api-key", capturedAuth)
        }

    @Test
    fun `ignores unknown fields in response`() =
        runBlocking {
            val body = """{
            "targetServices": {},
            "samplingRate": 0.1,
            "newFieldFromFutureVersion": true
        }"""

            val config = mockConfigClient(body = body).fetchConfig()

            assertNotNull(config)
            assertEquals(0.1, config.samplingRate)
        }
}
