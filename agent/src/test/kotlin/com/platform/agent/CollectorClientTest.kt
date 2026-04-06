package com.platform.agent

import com.platform.agent.models.BatchCapturedInputRequest
import com.platform.agent.models.CapturedInputRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectorClientTest {
    private fun testBatch(size: Int = 1) =
        BatchCapturedInputRequest(
            items =
                (1..size).map { i ->
                    CapturedInputRequest(
                        serviceId = "svc-123",
                        method = "GET",
                        url = "/api/orders/$i",
                        responseStatus = 200,
                        capturedAt = "2026-04-05T00:00:00Z",
                    )
                },
        )

    private fun mockCollector(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "{}",
        onRequest: (MockRequestHandleScope.(HttpRequestData) -> Unit)? = null,
    ): CollectorClient {
        val engine =
            MockEngine { request ->
                onRequest?.invoke(this, request)
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        val httpClient =
            HttpClient(engine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
        return CollectorClient(httpClient, "http://collector:8081", "test-api-key")
    }

    @Test
    fun `returns true on successful send`() =
        runBlocking {
            val client = mockCollector()

            val result = client.sendBatch(testBatch())

            assertTrue(result)
        }

    @Test
    fun `returns true for empty batch without making request`() =
        runBlocking {
            var requestMade = false
            val client = mockCollector(onRequest = { requestMade = true })

            val result = client.sendBatch(BatchCapturedInputRequest(items = emptyList()))

            assertTrue(result)
            assertFalse(requestMade)
        }

    @Test
    fun `returns false on non-success status`() =
        runBlocking {
            val client = mockCollector(status = HttpStatusCode.BadRequest)

            val result = client.sendBatch(testBatch())

            assertFalse(result)
        }

    @Test
    fun `returns false on server error`() =
        runBlocking {
            val client = mockCollector(status = HttpStatusCode.InternalServerError)

            val result = client.sendBatch(testBatch())

            assertFalse(result)
        }

    @Test
    fun `sends POST to correct endpoint with bearer auth`() =
        runBlocking {
            var capturedMethod: HttpMethod? = null
            var capturedUrl = ""
            var capturedAuth = ""

            val client =
                mockCollector(onRequest = { request ->
                    capturedMethod = request.method
                    capturedUrl = request.url.toString()
                    capturedAuth = request.headers[HttpHeaders.Authorization] ?: ""
                })

            client.sendBatch(testBatch())

            assertEquals(HttpMethod.Post, capturedMethod)
            assertTrue(capturedUrl.endsWith("/api/captured-inputs"))
            assertEquals("Bearer test-api-key", capturedAuth)
        }

    @Test
    fun `returns false on network exception`() =
        runBlocking {
            val engine =
                MockEngine {
                    throw java.io.IOException("Connection refused")
                }
            val httpClient =
                HttpClient(engine) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }
            val client = CollectorClient(httpClient, "http://collector:8081", "key")

            val result = client.sendBatch(testBatch())

            assertFalse(result)
        }
}
