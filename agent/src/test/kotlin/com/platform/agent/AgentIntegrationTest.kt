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
import kotlin.test.assertNull
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
            val transformer = TrafficTransformer(dynamicConfig)

            // Execute one iteration via the extracted function
            val newCursor =
                captureTraffic(null, 100, kubesharkClient, collectorClient, transformer)

            // Cursor advanced past the latest entry timestamp (max of all entries, not just matched)
            assertEquals(1004L, newCursor)

            // Collector received exactly one batch POST
            assertEquals(1, collectorRequestCount)

            // Verify the batch body has 2 items (e1 + e2, filtered e3 + e4)
            val batch =
                json.decodeFromString<BatchCapturedInputRequest>(collectorRequestBody)
            assertEquals(2, batch.items.size)
            assertEquals("GET", batch.items[0].method)
            assertEquals("/api/orders/1", batch.items[0].url)
            assertEquals("POST", batch.items[1].method)
            assertEquals("/api/orders", batch.items[1].url)
            assertTrue(batch.items.all { it.serviceId == "svc-123" })
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
            val collectorClient =
                CollectorClient(httpClient, "http://collector:8081", "key")
            val transformer = TrafficTransformer(dynamicConfig)

            // Even though Kubeshark returns entries, nothing passes the filter
            val newCursor =
                captureTraffic(null, 100, kubesharkClient, collectorClient, transformer)

            // Cursor still advances (entries were fetched, just nothing matched)
            assertEquals(1004L, newCursor)
        }

    @Test
    fun `pipeline handles kubeshark failure gracefully`(): Unit =
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
            val collectorClient =
                CollectorClient(httpClient, "http://collector:8081", "key")
            val transformer = TrafficTransformer(dynamicConfig)

            // Kubeshark failure → cursor unchanged
            val newCursor =
                captureTraffic(null, 100, kubesharkClient, collectorClient, transformer)

            assertNull(newCursor)
        }

    private fun MockRequestHandleScope.respondJson(body: String) =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
}
