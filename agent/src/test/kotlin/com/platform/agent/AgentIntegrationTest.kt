package com.platform.agent

import com.platform.agent.models.BatchCapturedInputRequest
import com.platform.agent.models.KubesharkContent
import com.platform.agent.models.KubesharkEndpoint
import com.platform.agent.models.KubesharkEntry
import com.platform.agent.models.KubesharkHeader
import com.platform.agent.models.KubesharkProtocol
import com.platform.agent.models.KubesharkRequest
import com.platform.agent.models.KubesharkResponse
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
 * Integration test wiring real components with mock backends.
 * Verifies the full pipeline: TrafficSource entries → transformer → collector POST.
 */
class AgentIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** In-memory TrafficSource returning fixed entries. */
    private class FakeTrafficSource(
        private val entries: List<KubesharkEntry>,
    ) : TrafficSource {
        override suspend fun listHttpCalls(
            startMs: Long?,
            limit: Int,
        ): List<KubesharkEntry> =
            entries
                .filter { startMs == null || it.timestamp >= startMs }
                .take(limit)
    }

    private val testEntries =
        listOf(
            // e1: HTTP GET to order-service — should be captured
            KubesharkEntry(
                id = "e1",
                timestamp = 1000,
                protocol = KubesharkProtocol(name = "http"),
                src = KubesharkEndpoint(ip = "10.0.0.1"),
                dst = KubesharkEndpoint(name = "order-service", ip = "10.0.0.2"),
                request =
                    KubesharkRequest(
                        method = "GET",
                        url = "/api/orders/1",
                        headers = listOf(KubesharkHeader("Accept", "application/json")),
                    ),
                response =
                    KubesharkResponse(
                        status = 200,
                        content = KubesharkContent(text = """{"id": 1}"""),
                    ),
            ),
            // e2: HTTP POST to order-service — should be captured
            KubesharkEntry(
                id = "e2",
                timestamp = 1001,
                protocol = KubesharkProtocol(name = "http"),
                src = KubesharkEndpoint(ip = "10.0.0.1"),
                dst = KubesharkEndpoint(name = "order-service", ip = "10.0.0.2"),
                request = KubesharkRequest(method = "POST", url = "/api/orders"),
                response = KubesharkResponse(status = 201),
            ),
            // e3: HTTP to unknown-service — should be filtered out (not a target)
            KubesharkEntry(
                id = "e3",
                timestamp = 1002,
                protocol = KubesharkProtocol(name = "http"),
                src = KubesharkEndpoint(ip = "10.0.0.1"),
                dst = KubesharkEndpoint(name = "unknown-service", ip = "10.0.0.3"),
                request = KubesharkRequest(method = "GET", url = "/health"),
                response = KubesharkResponse(status = 200),
            ),
            // e4: gRPC to order-service — should be filtered out (not HTTP)
            KubesharkEntry(
                id = "e4",
                timestamp = 1003,
                protocol = KubesharkProtocol(name = "grpc"),
                dst = KubesharkEndpoint(name = "order-service"),
                request = KubesharkRequest(method = "GetOrder", url = "/orders.OrderService/GetOrder"),
                response = KubesharkResponse(status = 0),
            ),
        )

    @Test
    fun `full pipeline filters and transforms kubeshark entries to collector batch`() =
        runBlocking {
            var collectorRequestCount = 0
            var collectorRequestBody = ""

            val engine =
                MockEngine { request ->
                    when {
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

            val trafficSource = FakeTrafficSource(testEntries)
            val collectorClient =
                CollectorClient(httpClient, "http://collector:8081", "key")
            val transformer = TrafficTransformer(dynamicConfig)

            val result =
                captureOneBatch(null, 100, trafficSource, collectorClient, transformer)

            // Cursor advanced past the latest entry timestamp (max of all entries, not just matched)
            assertEquals(1004L, result.cursor)

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
            val engine = MockEngine { respondJson("{}") }
            val httpClient =
                HttpClient(engine) {
                    install(ContentNegotiation) { json(json) }
                }

            val dynamicConfig =
                AtomicReference(
                    DynamicConfig(targetServices = emptyMap()),
                )

            val trafficSource = FakeTrafficSource(testEntries)
            val collectorClient =
                CollectorClient(httpClient, "http://collector:8081", "key")
            val transformer = TrafficTransformer(dynamicConfig)

            val result =
                captureOneBatch(null, 100, trafficSource, collectorClient, transformer)

            // Cursor still advances (entries were fetched, just nothing matched)
            assertEquals(1004L, result.cursor)
        }

    @Test
    fun `pipeline handles empty traffic source gracefully`(): Unit =
        runBlocking {
            val engine = MockEngine { respondJson("{}") }
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

            val trafficSource = FakeTrafficSource(emptyList())
            val collectorClient =
                CollectorClient(httpClient, "http://collector:8081", "key")
            val transformer = TrafficTransformer(dynamicConfig)

            val result =
                captureOneBatch(null, 100, trafficSource, collectorClient, transformer)

            assertNull(result.cursor)
        }

    private fun MockRequestHandleScope.respondJson(body: String) =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
}
