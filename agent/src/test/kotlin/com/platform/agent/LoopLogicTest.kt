package com.platform.agent

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
    inner class CaptureTrafficTests {
        private val emptyKubesharkResponse = """{"calls": [], "truncated": false}"""

        private fun mockClients(
            kubesharkBody: String = emptyKubesharkResponse,
            kubesharkStatus: HttpStatusCode = HttpStatusCode.OK,
            collectorStatus: HttpStatusCode = HttpStatusCode.OK,
            onCollectorRequest: ((String) -> Unit)? = null,
        ) = mockClients(
            kubesharkResponses = listOf(kubesharkBody),
            kubesharkStatus = kubesharkStatus,
            collectorStatus = collectorStatus,
            onCollectorRequest = onCollectorRequest,
        )

        private fun mockClients(
            kubesharkResponses: List<String>,
            kubesharkStatus: HttpStatusCode = HttpStatusCode.OK,
            collectorStatus: HttpStatusCode = HttpStatusCode.OK,
            onCollectorRequest: ((String) -> Unit)? = null,
        ): Triple<KubesharkClient, CollectorClient, TrafficTransformer> {
            var kubesharkCallIndex = 0
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.contains("/api/entries") -> {
                            val body =
                                kubesharkResponses[
                                    minOf(kubesharkCallIndex++, kubesharkResponses.lastIndex),
                                ]
                            respond(
                                content = body,
                                status = kubesharkStatus,
                                headers =
                                    headersOf(
                                        HttpHeaders.ContentType,
                                        "application/json",
                                    ),
                            )
                        }
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
                KubesharkClient(httpClient, "http://kubeshark:80"),
                CollectorClient(httpClient, "http://collector:8081", "key"),
                TrafficTransformer(dynamicConfig),
            )
        }

        private fun kubesharkResponseWith(vararg entries: Pair<Long, String>): String {
            val calls =
                entries.joinToString(",") { (ts, svc) ->
                    """{
                        "id": "e-$ts", "ts": $ts, "proto": "http",
                        "dst": {"svc": "$svc", "ip": "10.0.0.2"},
                        "method": "GET", "url": "/api/test", "status": 200
                    }"""
                }
            return """{"calls": [$calls], "truncated": false}"""
        }

        @Test
        fun `returns null cursor when no entries returned`() =
            runBlocking {
                val (kubeshark, collector, transformer) = mockClients()

                val result = captureTraffic(null, 100, kubeshark, collector, transformer)

                assertNull(result.cursor)
                assertEquals(0, result.pagesProcessed)
                assertEquals(0, result.entriesProcessed)
            }

        @Test
        fun `advances cursor to max timestamp plus one`() =
            runBlocking {
                val response =
                    kubesharkResponseWith(
                        5000L to "order-service",
                        3000L to "order-service",
                        7000L to "order-service",
                    )
                val (kubeshark, collector, transformer) = mockClients(kubesharkBody = response)

                val result = captureTraffic(null, 100, kubeshark, collector, transformer)

                assertEquals(7001L, result.cursor)
                assertEquals(1, result.pagesProcessed)
                assertEquals(3, result.entriesProcessed)
            }

        @Test
        fun `preserves existing cursor when kubeshark returns empty`() =
            runBlocking {
                val (kubeshark, collector, transformer) = mockClients()

                val result = captureTraffic(5000L, 100, kubeshark, collector, transformer)

                assertEquals(5000L, result.cursor)
            }

        @Test
        fun `does not call collector when all entries filtered out`() =
            runBlocking {
                var collectorCalled = false
                val response = kubesharkResponseWith(1000L to "unknown-service")
                val (kubeshark, collector, transformer) =
                    mockClients(
                        kubesharkBody = response,
                        onCollectorRequest = { collectorCalled = true },
                    )

                captureTraffic(null, 100, kubeshark, collector, transformer)

                assertFalse(collectorCalled)
            }

        @Test
        fun `still advances cursor when entries exist but all filtered`() =
            runBlocking {
                val response = kubesharkResponseWith(2000L to "unknown-service")
                val (kubeshark, collector, transformer) =
                    mockClients(kubesharkBody = response)

                val result = captureTraffic(null, 100, kubeshark, collector, transformer)

                assertEquals(2001L, result.cursor)
            }

        @Test
        fun `pages through all entries when batch is full`() =
            runBlocking {
                var collectorCallCount = 0
                val page1 =
                    kubesharkResponseWith(
                        1000L to "order-service",
                        2000L to "order-service",
                    )
                val page2 = kubesharkResponseWith(3000L to "order-service")
                val (kubeshark, collector, transformer) =
                    mockClients(
                        kubesharkResponses = listOf(page1, page2),
                        onCollectorRequest = { collectorCallCount++ },
                    )

                val result = captureTraffic(null, 2, kubeshark, collector, transformer)

                assertEquals(3001L, result.cursor)
                assertEquals(2, result.pagesProcessed)
                assertEquals(3, result.entriesProcessed)
                assertEquals(2, collectorCallCount)
            }

        @Test
        fun `stops paging when a page returns empty`() =
            runBlocking {
                val page1 =
                    kubesharkResponseWith(
                        1000L to "order-service",
                        2000L to "order-service",
                    )
                val page2 = """{"calls": [], "truncated": false}"""
                val (kubeshark, collector, transformer) =
                    mockClients(kubesharkResponses = listOf(page1, page2))

                val result = captureTraffic(null, 2, kubeshark, collector, transformer)

                assertEquals(2001L, result.cursor)
                assertEquals(1, result.pagesProcessed)
                assertEquals(2, result.entriesProcessed)
            }

        @Test
        fun `sends matching entries to collector`() =
            runBlocking {
                var receivedBody = ""
                val response = kubesharkResponseWith(1000L to "order-service")
                val (kubeshark, collector, transformer) =
                    mockClients(
                        kubesharkBody = response,
                        onCollectorRequest = { receivedBody = it },
                    )

                captureTraffic(null, 100, kubeshark, collector, transformer)

                val batch = json.decodeFromString<com.platform.agent.models.BatchCapturedInputRequest>(receivedBody)
                assertEquals(1, batch.items.size)
                assertEquals("svc-123", batch.items[0].serviceId)
            }

        @Test
        fun `reports lag when cursor is behind wall clock`() =
            runBlocking {
                val response = kubesharkResponseWith(1000L to "order-service")
                val (kubeshark, collector, transformer) = mockClients(kubesharkBody = response)

                // nowMs = 50_000, cursor will be 1001 → lag = 48_999ms
                val result = captureTraffic(null, 100, kubeshark, collector, transformer, nowMs = 50_000L)

                assertEquals(48_999L, result.lagMs)
            }

        @Test
        fun `reports no lag when cursor is close to wall clock`() =
            runBlocking {
                val response = kubesharkResponseWith(1000L to "order-service")
                val (kubeshark, collector, transformer) = mockClients(kubesharkBody = response)

                // nowMs = 1002, cursor will be 1001 → lag = 1ms
                val result = captureTraffic(null, 100, kubeshark, collector, transformer, nowMs = 1002L)

                assertEquals(1L, result.lagMs)
            }

        @Test
        fun `lag is null when no entries processed`() =
            runBlocking {
                val (kubeshark, collector, transformer) = mockClients()

                val result = captureTraffic(null, 100, kubeshark, collector, transformer)

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
