package com.platform.agent

import com.platform.agent.models.KubesharkEndpoint
import com.platform.agent.models.KubesharkEntry
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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoopLogicTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Nested
    inner class PollConfigTests {
        private fun configClientReturning(
            body: String? = null,
            status: HttpStatusCode = HttpStatusCode.OK,
        ): ConfigClient {
            val engine =
                MockEngine {
                    if (body != null) {
                        respondJson(body)
                    } else {
                        respond(
                            content = "error",
                            status = status,
                            headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                        )
                    }
                }
            val httpClient =
                HttpClient(engine) {
                    install(ContentNegotiation) { json(json) }
                }
            return ConfigClient(httpClient, "http://platform:8080", "key")
        }

        @Test
        fun `updates AtomicReference on successful fetch`() =
            runBlocking {
                val configClient =
                    configClientReturning(
                        body = """{
                            "targetServices": {"order-service": "svc-123"},
                            "samplingRate": 0.5
                        }""",
                    )
                val dynamicConfig = AtomicReference(DynamicConfig.default())

                val updated = pollConfig(configClient, dynamicConfig)

                assertTrue(updated)
                assertEquals(
                    mapOf("order-service" to "svc-123"),
                    dynamicConfig.get().targetServices,
                )
                assertEquals(0.5, dynamicConfig.get().samplingRate)
            }

        @Test
        fun `preserves old config when fetch fails`() =
            runBlocking {
                val configClient =
                    configClientReturning(status = HttpStatusCode.InternalServerError)
                val original =
                    DynamicConfig(
                        targetServices = mapOf("api-gateway" to "svc-456"),
                        samplingRate = 0.8,
                    )
                val dynamicConfig = AtomicReference(original)

                val updated = pollConfig(configClient, dynamicConfig)

                assertFalse(updated)
                assertEquals(original, dynamicConfig.get())
            }

        @Test
        fun `replaces entire config not just changed fields`() =
            runBlocking {
                val configClient =
                    configClientReturning(
                        body = """{
                            "targetServices": {"new-service": "svc-999"},
                            "samplingRate": 0.1,
                            "batchSize": 50
                        }""",
                    )
                val dynamicConfig =
                    AtomicReference(
                        DynamicConfig(
                            targetServices = mapOf("old-service" to "svc-111"),
                            samplingRate = 1.0,
                            batchSize = 200,
                        ),
                    )

                pollConfig(configClient, dynamicConfig)

                val config = dynamicConfig.get()
                assertEquals(mapOf("new-service" to "svc-999"), config.targetServices)
                assertEquals(0.1, config.samplingRate)
                assertEquals(50, config.batchSize)
            }
    }

    @Nested
    inner class CaptureOneBatchTests {
        private fun fakeTrafficSource(entries: List<KubesharkEntry> = emptyList()): TrafficSource =
            object : TrafficSource {
                override suspend fun listHttpCalls(
                    startMs: Long?,
                    limit: Int,
                ): List<KubesharkEntry> =
                    entries
                        .filter { startMs == null || it.timestamp >= startMs }
                        .take(limit)
            }

        private fun mockClients(
            entries: List<KubesharkEntry> = emptyList(),
            collectorStatus: HttpStatusCode = HttpStatusCode.OK,
            onCollectorRequest: ((String) -> Unit)? = null,
        ): Triple<TrafficSource, CollectorClient, TrafficTransformer> {
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.contains("/api/captured-inputs") -> {
                            onCollectorRequest?.invoke(
                                String(request.body.toByteArray(), Charsets.UTF_8),
                            )
                            respond(
                                content = "{}",
                                status = collectorStatus,
                                headers =
                                    headersOf(
                                        HttpHeaders.ContentType,
                                        "application/json",
                                    ),
                            )
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
            return Triple(
                fakeTrafficSource(entries),
                CollectorClient(httpClient, "http://collector:8081", "key"),
                TrafficTransformer(dynamicConfig),
            )
        }

        private fun httpEntry(
            timestamp: Long,
            dstName: String,
        ): KubesharkEntry =
            KubesharkEntry(
                id = "e-$timestamp",
                timestamp = timestamp,
                protocol = KubesharkProtocol(name = "http"),
                dst = KubesharkEndpoint(name = dstName, ip = "10.0.0.2"),
                request = KubesharkRequest(method = "GET", url = "/api/test"),
                response = KubesharkResponse(status = 200),
            )

        @Test
        fun `returns null cursor and caughtUp when no entries returned`() =
            runBlocking {
                val (source, collector, transformer) = mockClients()

                val result = captureOneBatch(null, 100, source, collector, transformer)

                assertNull(result.cursor)
                assertEquals(0, result.entriesProcessed)
                assertNull(result.lagMs)
                assertTrue(result.caughtUp)
            }

        @Test
        fun `advances cursor to max timestamp plus one`() =
            runBlocking {
                val entries =
                    listOf(
                        httpEntry(5000L, "order-service"),
                        httpEntry(3000L, "order-service"),
                        httpEntry(7000L, "order-service"),
                    )
                val (source, collector, transformer) = mockClients(entries = entries)

                val result = captureOneBatch(null, 100, source, collector, transformer)

                assertEquals(7001L, result.cursor)
                assertEquals(3, result.entriesProcessed)
            }

        @Test
        fun `preserves existing cursor when kubeshark returns empty`() =
            runBlocking {
                val (source, collector, transformer) = mockClients()

                val result = captureOneBatch(5000L, 100, source, collector, transformer)

                assertEquals(5000L, result.cursor)
                assertTrue(result.caughtUp)
            }

        @Test
        fun `does not call collector when all entries filtered out`() =
            runBlocking {
                var collectorCalled = false
                val entries = listOf(httpEntry(1000L, "unknown-service"))
                val (source, collector, transformer) =
                    mockClients(
                        entries = entries,
                        onCollectorRequest = { collectorCalled = true },
                    )

                captureOneBatch(null, 100, source, collector, transformer)

                assertFalse(collectorCalled)
            }

        @Test
        fun `still advances cursor when entries exist but all filtered`() =
            runBlocking {
                val entries = listOf(httpEntry(2000L, "unknown-service"))
                val (source, collector, transformer) =
                    mockClients(entries = entries)

                val result = captureOneBatch(null, 100, source, collector, transformer)

                assertEquals(2001L, result.cursor)
            }

        @Test
        fun `sends matching entries to collector`() =
            runBlocking {
                var receivedBody = ""
                val entries = listOf(httpEntry(1000L, "order-service"))
                val (source, collector, transformer) =
                    mockClients(
                        entries = entries,
                        onCollectorRequest = { receivedBody = it },
                    )

                captureOneBatch(null, 100, source, collector, transformer)

                val batch = json.decodeFromString<com.platform.agent.models.BatchCapturedInputRequest>(receivedBody)
                assertEquals(1, batch.items.size)
                assertEquals("svc-123", batch.items[0].serviceId)
            }

        @Test
        fun `caughtUp is true when entries less than batchSize`() =
            runBlocking {
                val entries = listOf(httpEntry(1000L, "order-service"))
                val (source, collector, transformer) = mockClients(entries = entries)

                val result = captureOneBatch(null, 100, source, collector, transformer)

                assertTrue(result.caughtUp)
            }

        @Test
        fun `caughtUp is false when entries equals batchSize`() =
            runBlocking {
                val entries =
                    listOf(
                        httpEntry(1000L, "order-service"),
                        httpEntry(2000L, "order-service"),
                    )
                val (source, collector, transformer) = mockClients(entries = entries)

                val result = captureOneBatch(null, 2, source, collector, transformer)

                assertFalse(result.caughtUp)
            }

        @Test
        fun `reports lag when cursor is behind wall clock`() =
            runBlocking {
                val entries = listOf(httpEntry(1000L, "order-service"))
                val (source, collector, transformer) = mockClients(entries = entries)

                val result = captureOneBatch(null, 100, source, collector, transformer, nowMs = 50_000L)

                assertEquals(48_999L, result.lagMs)
            }

        @Test
        fun `reports no lag when cursor is close to wall clock`() =
            runBlocking {
                val entries = listOf(httpEntry(1000L, "order-service"))
                val (source, collector, transformer) = mockClients(entries = entries)

                val result = captureOneBatch(null, 100, source, collector, transformer, nowMs = 1002L)

                assertEquals(1L, result.lagMs)
            }

        @Test
        fun `lag is null when no entries processed`() =
            runBlocking {
                val (source, collector, transformer) = mockClients()

                val result = captureOneBatch(null, 100, source, collector, transformer)

                assertNull(result.lagMs)
            }

        @Test
        fun `lag is null for sparse traffic with stale cursor`() =
            runBlocking {
                val (source, collector, transformer) = mockClients()

                val result = captureOneBatch(1000L, 100, source, collector, transformer, nowMs = 100_000L)

                assertEquals(1000L, result.cursor)
                assertNull(result.lagMs)
            }
    }

    private fun MockRequestHandleScope.respondJson(body: String) =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
}
