package com.platform.agent

import com.platform.agent.models.BatchCapturedInputRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test wiring real components with mock HTTP backends.
 * Verifies the full pipeline: Kubeshark response → transformer → collector POST.
 */
class AgentIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val kubesharkResponse = """{
        "calls": [
            {
                "id": "e1", "ts": 1000, "proto": "http",
                "src": {"ip": "10.0.0.1"},
                "dst": {"svc": "order-service", "ip": "10.0.0.2"},
                "method": "GET", "url": "/api/orders/1", "status": 200,
                "req_headers": {"Accept": "application/json"},
                "resp_body": "{\"id\": 1}"
            },
            {
                "id": "e2", "ts": 1001, "proto": "http",
                "src": {"ip": "10.0.0.1"},
                "dst": {"svc": "order-service", "ip": "10.0.0.2"},
                "method": "POST", "url": "/api/orders", "status": 201,
                "req_body": "{\"item\": \"widget\"}"
            },
            {
                "id": "e3", "ts": 1002, "proto": "http",
                "src": {"ip": "10.0.0.1"},
                "dst": {"svc": "unknown-service", "ip": "10.0.0.3"},
                "method": "GET", "url": "/health", "status": 200
            },
            {
                "id": "e4", "ts": 1003, "proto": "grpc",
                "dst": {"svc": "order-service"},
                "method": "GetOrder", "url": "/orders.OrderService/GetOrder", "status": 0
            }
        ],
        "truncated": false
    }"""

    @Test
    fun `full pipeline filters and transforms kubeshark entries to collector batch`() =
        runBlocking {
            var collectorRequestCount = 0
            var collectorRequestBody = ""

            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.contains("/api/entries") ->
                            respondJson(kubesharkResponse)
                        request.url.encodedPath.contains("/api/captured-inputs") -> {
                            collectorRequestCount++
                            collectorRequestBody =
                                String(request.body.toByteArray(), Charsets.UTF_8)
                            respondJson("""{"accepted": true}""")
                        }
                        else -> respondJson("{}")
                    }
                }

            val httpClient =
                HttpClient(engine) {
                    install(ContentNegotiation) { json(json) }
                }

            val dynamicConfig =
                AtomicReference(
                    DynamicConfig(
                        targetServices = mapOf("order-service" to "svc-123"),
                        samplingRate = 1.0,
                    ),
                )

            val kubesharkClient = KubesharkClient(httpClient, "http://kubeshark:80")
            val collectorClient =
                CollectorClient(httpClient, "http://collector:8081", "key")
            val transformer = TrafficTransformer(dynamicConfig, random = { 0.0 })

            // Execute one iteration of the capture pipeline
            val entries = kubesharkClient.listHttpCalls(limit = 100)
            val captured = transformer.transform(entries)
            if (captured.isNotEmpty()) {
                collectorClient.sendBatch(
                    BatchCapturedInputRequest(items = captured),
                )
            }

            // e1 + e2 captured (order-service, HTTP)
            // e3 filtered (unknown service), e4 filtered (grpc)
            assertEquals(2, captured.size)
            assertEquals("GET", captured[0].method)
            assertEquals("/api/orders/1", captured[0].url)
            assertEquals("POST", captured[1].method)
            assertEquals("/api/orders", captured[1].url)

            // Both mapped to correct platform service ID
            assertTrue(captured.all { it.serviceId == "svc-123" })

            // Headers and bodies preserved through the pipeline
            assertEquals(
                mapOf("Accept" to "application/json"),
                captured[0].requestHeaders,
            )
            assertEquals("""{"id": 1}""", captured[0].responseBody)
            assertEquals("""{"item": "widget"}""", captured[1].requestBody)

            // Collector received exactly one batch POST
            assertEquals(1, collectorRequestCount)

            // Verify the batch body is valid JSON with 2 items
            val batch =
                json.decodeFromString<BatchCapturedInputRequest>(collectorRequestBody)
            assertEquals(2, batch.items.size)
        }

    @Test
    fun `pipeline produces nothing when no target services configured`() =
        runBlocking {
            val engine = MockEngine { respondJson(kubesharkResponse) }
            val httpClient =
                HttpClient(engine) {
                    install(ContentNegotiation) { json(json) }
                }

            val dynamicConfig =
                AtomicReference(
                    DynamicConfig(targetServices = emptyMap()),
                )

            val kubesharkClient =
                KubesharkClient(httpClient, "http://kubeshark:80")
            val transformer = TrafficTransformer(dynamicConfig, random = { 0.0 })

            val entries = kubesharkClient.listHttpCalls()
            val captured = transformer.transform(entries)

            assertTrue(captured.isEmpty())
        }

    @Test
    fun `pipeline handles kubeshark failure gracefully`() =
        runBlocking {
            val engine =
                MockEngine {
                    respond(
                        content = "Internal Server Error",
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                    )
                }
            val httpClient =
                HttpClient(engine) {
                    install(ContentNegotiation) { json(json) }
                }

            val dynamicConfig =
                AtomicReference(
                    DynamicConfig(
                        targetServices = mapOf("order-service" to "svc-123"),
                    ),
                )

            val kubesharkClient = KubesharkClient(httpClient, "http://kubeshark:80")
            val transformer = TrafficTransformer(dynamicConfig, random = { 0.0 })

            val entries = kubesharkClient.listHttpCalls()
            val captured = transformer.transform(entries)

            assertTrue(entries.isEmpty())
            assertTrue(captured.isEmpty())
        }

    private fun MockRequestHandleScope.respondJson(body: String) =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
}
