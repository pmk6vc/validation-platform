package com.platform.agent

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformClientTest {
    private fun mockClient(
        status: HttpStatusCode = HttpStatusCode.Created,
        body: String = "",
        onRequest: ((HttpRequestData) -> Unit)? = null,
    ): PlatformClient {
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
        return PlatformClient(httpClient, "http://platform:8080", "test-api-key")
    }

    @Test
    fun `returns true on 201 Created`() =
        runBlocking {
            assertTrue(mockClient(status = HttpStatusCode.Created).registerService("production", "api-gateway"))
        }

    @Test
    fun `returns true on 200 OK`() =
        runBlocking {
            assertTrue(mockClient(status = HttpStatusCode.OK).registerService("production", "api-gateway"))
        }

    @Test
    fun `treats 409 Conflict as success (idempotent re-registration)`() =
        runBlocking {
            assertTrue(mockClient(status = HttpStatusCode.Conflict).registerService("production", "api-gateway"))
        }

    @Test
    fun `returns false on 5xx`() =
        runBlocking {
            assertFalse(
                mockClient(status = HttpStatusCode.InternalServerError, body = "boom")
                    .registerService("production", "api-gateway"),
            )
        }

    @Test
    fun `returns false on 401 Unauthorized`() =
        runBlocking {
            assertFalse(mockClient(status = HttpStatusCode.Unauthorized).registerService("production", "api-gateway"))
        }

    @Test
    fun `sends bearer token and POSTs to api services`() =
        runBlocking {
            var captured: HttpRequestData? = null
            val client = mockClient(onRequest = { captured = it })

            client.registerService("production", "api-gateway")

            val request = requireNotNull(captured)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("http://platform:8080/api/services", request.url.toString())
            assertEquals("Bearer test-api-key", request.headers[HttpHeaders.Authorization])
        }

    @Test
    fun `sends application json content type`() =
        runBlocking {
            var captured: HttpRequestData? = null
            val client = mockClient(onRequest = { captured = it })

            client.registerService("production", "order-service")

            val contentType = captured!!.body.contentType.toString()
            assertEquals("application/json", contentType)
        }
}
