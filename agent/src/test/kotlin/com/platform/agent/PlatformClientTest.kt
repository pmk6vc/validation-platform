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
    fun `Success on 201 Created`() =
        runBlocking {
            assertEquals(
                RegistrationOutcome.Success,
                mockClient(status = HttpStatusCode.Created).registerService("production", "api-gateway"),
            )
        }

    @Test
    fun `Success on 200 OK`() =
        runBlocking {
            assertEquals(
                RegistrationOutcome.Success,
                mockClient(status = HttpStatusCode.OK).registerService("production", "api-gateway"),
            )
        }

    @Test
    fun `Success on 409 Conflict (idempotent re-registration)`() =
        runBlocking {
            assertEquals(
                RegistrationOutcome.Success,
                mockClient(status = HttpStatusCode.Conflict).registerService("production", "api-gateway"),
            )
        }

    @Test
    fun `TransientFailure on 5xx (retry next tick)`() =
        runBlocking {
            assertEquals(
                RegistrationOutcome.TransientFailure,
                mockClient(status = HttpStatusCode.InternalServerError, body = "boom")
                    .registerService("production", "api-gateway"),
            )
        }

    @Test
    fun `TransientFailure on 503 Service Unavailable`() =
        runBlocking {
            assertEquals(
                RegistrationOutcome.TransientFailure,
                mockClient(status = HttpStatusCode.ServiceUnavailable)
                    .registerService("production", "api-gateway"),
            )
        }

    @Test
    fun `PermanentRejection on 400 Bad Request`() =
        runBlocking {
            assertEquals(
                RegistrationOutcome.PermanentRejection,
                mockClient(status = HttpStatusCode.BadRequest, body = "invalid name")
                    .registerService("production", "api-gateway"),
            )
        }

    @Test
    fun `PermanentRejection on 422 Unprocessable Entity`() =
        runBlocking {
            assertEquals(
                RegistrationOutcome.PermanentRejection,
                mockClient(status = HttpStatusCode.UnprocessableEntity)
                    .registerService("production", "api-gateway"),
            )
        }

    @Test
    fun `TransientFailure on 401 Unauthorized (caller-level, not service-level)`() =
        runBlocking {
            // 401 affects every service equally — adding individual services
            // to permanentlyFailed would quietly poison them so they never
            // re-register after the token is rotated/refreshed.
            assertEquals(
                RegistrationOutcome.TransientFailure,
                mockClient(status = HttpStatusCode.Unauthorized).registerService("production", "api-gateway"),
            )
        }

    @Test
    fun `TransientFailure on 403 Forbidden (caller-level)`() =
        runBlocking {
            assertEquals(
                RegistrationOutcome.TransientFailure,
                mockClient(status = HttpStatusCode.Forbidden).registerService("production", "api-gateway"),
            )
        }

    @Test
    fun `TransientFailure on 404 Not Found (wrong endpoint, not a per-service problem)`() =
        runBlocking {
            assertEquals(
                RegistrationOutcome.TransientFailure,
                mockClient(status = HttpStatusCode.NotFound).registerService("production", "api-gateway"),
            )
        }

    @Test
    fun `TransientFailure on 429 Too Many Requests (rate limit, retry later)`() =
        runBlocking {
            assertEquals(
                RegistrationOutcome.TransientFailure,
                mockClient(status = HttpStatusCode.TooManyRequests).registerService("production", "api-gateway"),
            )
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
