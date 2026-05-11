package com.platform.agent

import com.platform.agent.models.BatchCapturedInputRequest
import com.platform.agent.models.KubesharkEndpoint
import com.platform.agent.models.KubesharkEntry
import com.platform.agent.models.KubesharkPod
import com.platform.agent.models.KubesharkPodMetadata
import com.platform.agent.models.KubesharkProtocol
import com.platform.agent.models.KubesharkRequest
import com.platform.agent.models.KubesharkResponse
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
            val httpClient = buildAgentPlatformHttpClient(engine)
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
                val dynamicConfig = MutableStateFlow(DynamicConfig.default())

                val updated = pollConfig(configClient, dynamicConfig)

                assertTrue(updated)
                assertEquals(
                    mapOf("order-service" to "svc-123"),
                    dynamicConfig.value.targetServices,
                )
                assertEquals(0.5, dynamicConfig.value.samplingRate)
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
                val dynamicConfig = MutableStateFlow(original)

                val updated = pollConfig(configClient, dynamicConfig)

                assertFalse(updated)
                assertEquals(original, dynamicConfig.value)
            }

        @Test
        fun `fields omitted from new config revert to defaults, not old values`() =
            runBlocking {
                // Old config has a non-default batchSize (200). The new
                // config JSON does NOT mention batchSize at all. If the
                // update is a full replacement (decode JSON -> new object),
                // batchSize must revert to the default (100). A merge
                // strategy would instead preserve the old 200.
                //
                // Same trick for discoveryInterval: old is 120s (non-default),
                // new JSON omits it, so it should revert to default 60s.
                val configClient =
                    configClientReturning(
                        body = """{
                            "targetServices": {"new-service": "svc-999"},
                            "samplingRate": 0.1
                        }""",
                    )
                val dynamicConfig =
                    MutableStateFlow(
                        DynamicConfig(
                            targetServices = mapOf("old-service" to "svc-111"),
                            samplingRate = 1.0,
                            batchSize = 200,
                            discoveryInterval = 120.seconds,
                        ),
                    )

                pollConfig(configClient, dynamicConfig)

                val config = dynamicConfig.value
                // Changed fields take the new values
                assertEquals(mapOf("new-service" to "svc-999"), config.targetServices)
                assertEquals(0.1, config.samplingRate)
                // Omitted fields revert to defaults (this is the actual assertion)
                assertEquals(
                    DynamicConfig().batchSize,
                    config.batchSize,
                    "omitted batchSize should revert to default, not stay at the old 200",
                )
                assertEquals(
                    DynamicConfig().discoveryInterval,
                    config.discoveryInterval,
                    "omitted discoveryInterval should revert to default, not stay at the old 120s",
                )
            }
    }

    @Nested
    inner class CaptureOneBatchTests {
        private val defaultMaxWait = 1.seconds

        /**
         * Build a [KubesharkClient] mock that returns the given [entries] from
         * [KubesharkClient.drainBatch], capped at `limit`. Since `drainBatch`
         * in the real client pulls from a Channel, the mock ignores `maxWait`
         * and just returns whatever fits.
         */
        private fun fakeKubesharkClient(
            entries: List<KubesharkEntry> = emptyList(),
            connected: Boolean = true,
        ): KubesharkClient {
            val client = mockk<KubesharkClient>()
            coEvery { client.drainBatch(any<Int>(), any<Duration>()) } answers {
                val limit = firstArg<Int>()
                entries.take(limit)
            }
            every { client.isConnected() } returns connected
            return client
        }

        private fun mockClients(
            entries: List<KubesharkEntry> = emptyList(),
            collectorStatus: HttpStatusCode = HttpStatusCode.OK,
            onCollectorRequest: ((String) -> Unit)? = null,
            connected: Boolean = true,
        ): Triple<KubesharkClient, CollectorClient, TrafficTransformer> {
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.contains("/api/captured-inputs") -> {
                            onCollectorRequest?.invoke(request.bodyAsDecodedString())
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
            val httpClient = buildAgentCollectorHttpClient(engine)
            val dynamicConfig =
                MutableStateFlow(
                    DynamicConfig(
                        targetServices = mapOf("order-service" to "svc-123"),
                        samplingRate = 1.0,
                    ),
                )
            return Triple(
                fakeKubesharkClient(entries, connected = connected),
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
                dst =
                    KubesharkEndpoint(
                        name = dstName,
                        ip = "10.0.0.2",
                        pod =
                            KubesharkPod(
                                metadata = KubesharkPodMetadata(labels = mapOf("app" to dstName)),
                            ),
                    ),
                request = KubesharkRequest(method = "GET", url = "/api/test"),
                response = KubesharkResponse(status = 200),
            )

        @Test
        fun `returns zero processed and null lag when channel is empty`() =
            runBlocking {
                val (client, collector, transformer) = mockClients()

                val result = captureOneBatch(100, defaultMaxWait, client, collector, transformer)

                assertEquals(0, result.entriesProcessed)
                assertNull(result.lag)
            }

        @Test
        fun `does not call collector when all entries filtered out`() =
            runBlocking {
                var collectorCalled = false
                val entries = listOf(httpEntry(1000L, "unknown-service"))
                val (client, collector, transformer) =
                    mockClients(
                        entries = entries,
                        onCollectorRequest = { collectorCalled = true },
                    )

                captureOneBatch(100, defaultMaxWait, client, collector, transformer)

                assertFalse(collectorCalled)
            }

        @Test
        fun `sends matching entries to collector`() =
            runBlocking {
                var receivedBody = ""
                val entries = listOf(httpEntry(1000L, "order-service"))
                val (client, collector, transformer) =
                    mockClients(
                        entries = entries,
                        onCollectorRequest = { receivedBody = it },
                    )

                captureOneBatch(100, defaultMaxWait, client, collector, transformer)

                val batch = json.decodeFromString<BatchCapturedInputRequest>(receivedBody)
                assertEquals(1, batch.items.size)
                assertEquals("svc-123", batch.items[0].serviceId)
            }

        @Test
        fun `reports entries processed count including filtered-out ones`() =
            runBlocking {
                val entries =
                    listOf(
                        httpEntry(1000L, "order-service"),
                        httpEntry(2000L, "unknown-service"),
                        httpEntry(3000L, "order-service"),
                    )
                val (client, collector, transformer) = mockClients(entries = entries)

                val result = captureOneBatch(100, defaultMaxWait, client, collector, transformer)

                // All 3 raw entries were drained — entriesProcessed tracks raw, not filtered
                assertEquals(3, result.entriesProcessed)
            }

        @Test
        fun `reports lag based on newest entry timestamp`() =
            runBlocking {
                val entries =
                    listOf(
                        httpEntry(1000L, "order-service"),
                        httpEntry(3000L, "order-service"),
                        httpEntry(2000L, "order-service"),
                    )
                val (client, collector, transformer) = mockClients(entries = entries)

                val result =
                    captureOneBatch(
                        batchSize = 100,
                        maxWait = defaultMaxWait,
                        kubesharkClient = client,
                        collectorClient = collector,
                        transformer = transformer,
                        nowMs = 50_000L,
                    )

                // Lag = now - max(timestamps) = 50000 - 3000 = 47000ms = 47s
                assertEquals(47.seconds, result.lag)
            }

        @Test
        fun `reports small lag when newest entry is close to wall clock`() =
            runBlocking {
                val entries = listOf(httpEntry(1000L, "order-service"))
                val (client, collector, transformer) = mockClients(entries = entries)

                val result =
                    captureOneBatch(
                        batchSize = 100,
                        maxWait = defaultMaxWait,
                        kubesharkClient = client,
                        collectorClient = collector,
                        transformer = transformer,
                        nowMs = 1002L,
                    )

                assertEquals(2.milliseconds, result.lag)
            }

        @Test
        fun `lag is null when no entries drained`() =
            runBlocking {
                val (client, collector, transformer) = mockClients()

                val result = captureOneBatch(100, defaultMaxWait, client, collector, transformer)

                assertNull(result.lag)
            }

        @Test
        fun `heartbeat fires after drain even when collector is hanging in retries`() =
            runBlocking {
                var heartbeats = 0
                val entries = listOf(httpEntry(1000L, "order-service"))
                val (client, _, transformer) = mockClients(entries = entries)
                // Collector permanently returns 5xx → sendBatch retries forever. With OPS-1
                // fixed, the heartbeat must fire after drain returns, before the retry loop
                // can starve it.
                val brokenHttpClient =
                    buildAgentCollectorHttpClient(
                        MockEngine {
                            respond(content = "boom", status = HttpStatusCode.InternalServerError)
                        },
                    )
                val brokenCollector =
                    CollectorClient(
                        httpClient = brokenHttpClient,
                        baseUrl = "http://collector:8081",
                        authToken = "key",
                        initialBackoff = 10.milliseconds,
                        maxBackoff = 10.milliseconds,
                    )

                kotlinx.coroutines.withTimeoutOrNull(500) {
                    captureOneBatch(
                        batchSize = 100,
                        maxWait = defaultMaxWait,
                        kubesharkClient = client,
                        collectorClient = brokenCollector,
                        transformer = transformer,
                        heartbeat = { heartbeats++ },
                    )
                }

                assertEquals(1, heartbeats, "heartbeat should fire once after successful drain")
            }

        @Test
        fun `heartbeat fires on empty drain when Kubeshark session is connected (idle)`() =
            runBlocking {
                var heartbeats = 0
                // Healthy idle: WebSocket is open, no production traffic flowing.
                // Heartbeat must fire so the liveness probe doesn't restart a quiet pod.
                val (client, collector, transformer) =
                    mockClients(entries = emptyList(), connected = true)

                captureOneBatch(
                    batchSize = 100,
                    maxWait = defaultMaxWait,
                    kubesharkClient = client,
                    collectorClient = collector,
                    transformer = transformer,
                    heartbeat = { heartbeats++ },
                )

                assertEquals(1, heartbeats)
            }

        @Test
        fun `heartbeat does not fire on empty drain when Kubeshark session is disconnected`() =
            runBlocking {
                var heartbeats = 0
                // Broken: streamerJob is in its reconnect delay, channel is empty.
                // Heartbeat must NOT fire so the liveness probe fails and the pod restarts.
                val (client, collector, transformer) =
                    mockClients(entries = emptyList(), connected = false)

                captureOneBatch(
                    batchSize = 100,
                    maxWait = defaultMaxWait,
                    kubesharkClient = client,
                    collectorClient = collector,
                    transformer = transformer,
                    heartbeat = { heartbeats++ },
                )

                assertEquals(0, heartbeats)
            }

        @Test
        fun `heartbeat fires once per successful drain even if all entries filtered out`() =
            runBlocking {
                var heartbeats = 0
                // Entries drained from Kubeshark (proves WebSocket is alive), but transform
                // filters them all out. Heartbeat should still fire — pipeline is healthy.
                val entries = listOf(httpEntry(1000L, "unknown-service"))
                val (client, collector, transformer) = mockClients(entries = entries)

                captureOneBatch(
                    batchSize = 100,
                    maxWait = defaultMaxWait,
                    kubesharkClient = client,
                    collectorClient = collector,
                    transformer = transformer,
                    heartbeat = { heartbeats++ },
                )

                assertEquals(1, heartbeats)
            }
    }

    private fun MockRequestHandleScope.respondJson(body: String) =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
}
