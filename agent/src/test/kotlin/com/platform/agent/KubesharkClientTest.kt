package com.platform.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KubesharkClientTest {
    private fun mockKubesharkClient(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = """{"calls": [], "truncated": false}""",
    ): KubesharkClient {
        val engine =
            MockEngine {
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
        return KubesharkClient(httpClient, "http://kubeshark:80")
    }

    @Test
    fun `returns entries from successful response`() =
        runBlocking {
            val responseBody = """{
            "calls": [
                {
                    "id": "entry-1",
                    "ts": 1000,
                    "proto": "http",
                    "method": "GET",
                    "url": "/api/orders",
                    "status": 200
                }
            ],
            "truncated": false
        }"""

            val client = mockKubesharkClient(body = responseBody)
            val entries = client.listHttpCalls()

            assertEquals(1, entries.size)
            assertEquals("entry-1", entries[0].id)
            assertEquals("GET", entries[0].method)
            assertEquals("/api/orders", entries[0].url)
            assertEquals(200, entries[0].status)
        }

    @Test
    fun `returns empty list on non-success status`() =
        runBlocking {
            val client =
                mockKubesharkClient(
                    status = HttpStatusCode.InternalServerError,
                    body = "server error",
                )

            val entries = client.listHttpCalls()

            assertTrue(entries.isEmpty())
        }

    @Test
    fun `returns empty list on malformed JSON`() =
        runBlocking {
            val client = mockKubesharkClient(body = "not json at all")

            val entries = client.listHttpCalls()

            assertTrue(entries.isEmpty())
        }

    @Test
    fun `passes query parameters correctly`() =
        runBlocking {
            var capturedUrl = ""
            val engine =
                MockEngine { request ->
                    capturedUrl = request.url.toString()
                    respond(
                        content = """{"calls": [], "truncated": false}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val httpClient =
                HttpClient(engine) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }
            val client = KubesharkClient(httpClient, "http://kubeshark:80")

            client.listHttpCalls(startMs = 5000L, limit = 50)

            assertTrue(capturedUrl.contains("start=5000"))
            assertTrue(capturedUrl.contains("limit=50"))
            assertTrue(capturedUrl.contains("q=http"))
            assertTrue(capturedUrl.contains("format=full"))
        }

    @Test
    fun `omits start parameter when null`() =
        runBlocking {
            var capturedUrl = ""
            val engine =
                MockEngine { request ->
                    capturedUrl = request.url.toString()
                    respond(
                        content = """{"calls": [], "truncated": false}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val httpClient =
                HttpClient(engine) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }
            val client = KubesharkClient(httpClient, "http://kubeshark:80")

            client.listHttpCalls(startMs = null, limit = 100)

            assertTrue(!capturedUrl.contains("start="))
        }

    @Test
    fun `handles entries with unknown fields gracefully`() =
        runBlocking {
            val responseBody = """{
            "calls": [
                {
                    "id": "entry-1",
                    "ts": 1000,
                    "proto": "http",
                    "newField": "should be ignored",
                    "method": "POST",
                    "url": "/api/orders",
                    "status": 201
                }
            ],
            "truncated": false
        }"""

            val client = mockKubesharkClient(body = responseBody)
            val entries = client.listHttpCalls()

            assertEquals(1, entries.size)
            assertEquals("POST", entries[0].method)
        }
}
