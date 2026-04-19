package com.platform.agent

import com.platform.agent.models.BatchCapturedInputRequest
import com.platform.agent.models.CapturedInputRequest
import com.platform.agent.models.KubesharkContent
import com.platform.agent.models.KubesharkEndpoint
import com.platform.agent.models.KubesharkEntry
import com.platform.agent.models.KubesharkHeader
import com.platform.agent.models.KubesharkPostData
import com.platform.agent.models.KubesharkProtocol
import com.platform.agent.models.KubesharkRequest
import com.platform.agent.models.KubesharkResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/**
 * Integration tests for the agent's traffic **capture pipeline**: the chain
 * that runs from Kubeshark WebSocket → [KubesharkClient]'s channel →
 * [captureOneBatch] → [TrafficTransformer] → [CollectorClient] POST.
 *
 * The `ConfigClient` and service-discovery loops are **intentionally out of
 * scope** here — they're tested in isolation in [LoopLogicTest]. This file
 * focuses on the one part of the agent where real transport, real
 * backpressure, and real error recovery meaningfully interact: the data
 * path from Kubeshark to the collector.
 *
 * Every test uses [withWiredPipeline] to stand up:
 *   - an embedded Ktor Netty server as a fake Kubeshark hub
 *   - a real [KubesharkClient] connected over a real WebSocket
 *   - a real [TrafficTransformer]
 *   - a real [CollectorClient] backed by a [MockEngine] HTTP client so tests
 *     can script responses and inspect every received batch
 */
class CapturePipelineIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    // -----------------------------------------------------------------------
    // Test harness
    //
    // Real KubesharkClient wired to an embedded Ktor WebSocket server, real
    // CollectorClient backed by a MockEngine. Used by every test in this file
    // so we exercise real transport, real backpressure, real reconnect, and
    // real error handling.
    // -----------------------------------------------------------------------

    /**
     * A fully-wired pipeline: real [KubesharkClient] connected to an embedded
     * Ktor WebSocket server, real [CollectorClient] backed by a Ktor [MockEngine]
     * so we can inspect and control collector HTTP behavior.
     *
     * Test bodies receive a [WiredPipeline] and call [captureBatch] to step
     * through the pipeline as if the real capture loop were running.
     */
    private class WiredPipeline(
        val kubesharkClient: KubesharkClient,
        val collectorClient: CollectorClient,
        val transformer: TrafficTransformer,
        val collectorRequests: MutableList<BatchCapturedInputRequest>,
        val collectorFailureCount: AtomicInteger,
        val configFlow: MutableStateFlow<DynamicConfig>,
    ) {
        suspend fun captureBatch(
            batchSize: Int = 100,
            maxWait: Duration = 2.seconds,
        ): CaptureResult =
            captureOneBatch(
                batchSize = batchSize,
                maxWait = maxWait,
                kubesharkClient = kubesharkClient,
                collectorClient = collectorClient,
                transformer = transformer,
            )
    }

    /**
     * Spin up an embedded Ktor server with a `/api/wsFull` handler, wire a real
     * [KubesharkClient] to it, and invoke [block] with the [WiredPipeline].
     * Cleans up the server, the HTTP clients, and the streamer coroutine on exit.
     *
     * @param wsHandler Called each time the client establishes a WebSocket
     *   session. Tests that need reconnect behavior can inspect the connection
     *   index to decide what to send.
     * @param channelCapacity Small values force backpressure.
     * @param reconnectDelay Short values make reconnect tests fast.
     * @param collectorResponder Returns the status the collector should respond
     *   with for a given request index (1-based). Default: always OK.
     */
    private fun withWiredPipeline(
        wsHandler: suspend DefaultWebSocketServerSession.(connectionIndex: Int) -> Unit,
        channelCapacity: Int = KubesharkClient.DEFAULT_CHANNEL_CAPACITY,
        reconnectDelay: Duration = 50.milliseconds,
        collectorResponder: (requestIndex: Int) -> HttpStatusCode = { HttpStatusCode.OK },
        dynamicConfig: DynamicConfig =
            DynamicConfig(
                targetServices = mapOf("order-service" to "svc-123"),
                samplingRate = 1.0,
            ),
        block: suspend CoroutineScope.(WiredPipeline) -> Unit,
    ) = runBlocking {
        val connectionCounter = AtomicInteger(0)
        val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
            embeddedServer(Netty, port = 0) {
                install(ServerWebSockets)
                routing {
                    webSocket("/api/wsFull") {
                        // Consume the KFL filter frame sent by KubesharkClient
                        incoming.receive()
                        val index = connectionCounter.incrementAndGet()
                        wsHandler(index)
                    }
                }
            }
        server.start(wait = false)
        val port =
            server.engine
                .resolvedConnectors()
                .first()
                .port

        // Real WebSocket client for KubesharkClient
        val wsHttpClient =
            HttpClient(CIO) {
                install(WebSockets)
            }

        // MockEngine-backed HTTP client for CollectorClient. We record every
        // batch body and let the test control which responses come back.
        val collectorRequests = mutableListOf<BatchCapturedInputRequest>()
        val collectorFailureCount = AtomicInteger(0)
        val collectorRequestCounter = AtomicInteger(0)
        val collectorEngine =
            MockEngine { request ->
                when {
                    request.url.encodedPath.contains("/api/captured-inputs") -> {
                        val bodyString = String(request.body.toByteArray(), Charsets.UTF_8)
                        val batch = json.decodeFromString<BatchCapturedInputRequest>(bodyString)
                        synchronized(collectorRequests) {
                            collectorRequests.add(batch)
                        }
                        val index = collectorRequestCounter.incrementAndGet()
                        val status = collectorResponder(index)
                        if (!status.isSuccess()) collectorFailureCount.incrementAndGet()
                        respondJson("""{"accepted": ${status.isSuccess()}}""", status)
                    }
                    else -> respondJson("{}")
                }
            }
        val collectorHttpClient =
            HttpClient(collectorEngine) {
                install(ContentNegotiation) { json(json) }
            }

        val clientScope = CoroutineScope(coroutineContext + Job())
        try {
            val configFlow = MutableStateFlow(dynamicConfig)
            val kubesharkClient =
                KubesharkClient(
                    httpClient = wsHttpClient,
                    baseUrl = "http://127.0.0.1:$port",
                    scope = clientScope,
                    configFlow = configFlow,
                    capacity = channelCapacity,
                    reconnectDelay = reconnectDelay,
                )
            val collectorClient =
                CollectorClient(
                    httpClient = collectorHttpClient,
                    baseUrl = "http://collector:8081",
                    authToken = "key",
                    // Fast backoff so retry-recovery tests don't stall
                    initialBackoff = 5.milliseconds,
                    maxBackoff = 50.milliseconds,
                )
            val transformer = TrafficTransformer(configFlow)

            val pipeline =
                WiredPipeline(
                    kubesharkClient = kubesharkClient,
                    collectorClient = collectorClient,
                    transformer = transformer,
                    collectorRequests = collectorRequests,
                    collectorFailureCount = collectorFailureCount,
                    configFlow = configFlow,
                )
            block(pipeline)
        } finally {
            clientScope.cancel()
            wsHttpClient.close()
            collectorHttpClient.close()
            server.stop(100, 100)
        }
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    // -----------------------------------------------------------------------
    // Fixture helpers
    // -----------------------------------------------------------------------

    /**
     * Serialize a [KubesharkEntry] to the wire format the real Kubeshark
     * server emits. Tests use this to push typed fixtures through the
     * embedded WebSocket server as JSON text frames.
     */
    private fun entryJson(entry: KubesharkEntry): String = json.encodeToString(KubesharkEntry.serializer(), entry)

    /**
     * Build a JSON frame for a minimal HTTP GET entry. [dstName] controls which
     * service the entry targets — defaults to `order-service` so existing
     * failure-mode tests that just need "some identifiable entry" continue to
     * work without changes.
     */
    private fun entry(
        id: String,
        timestamp: Long,
        body: String? = null,
        dstName: String = "order-service",
    ): String {
        val contentField =
            if (body != null) {
                """, "content": {"text": "$body", "mimeType": "application/json"}"""
            } else {
                ""
            }
        return """{
            "id": "$id",
            "timestamp": $timestamp,
            "protocol": {"name": "http", "abbr": "HTTP"},
            "tls": false,
            "src": {"ip": "10.0.0.1", "port": "45678", "name": "client", "namespace": "production"},
            "dst": {"ip": "10.0.0.2", "port": "8080", "name": "$dstName", "namespace": "production"},
            "request": {"method": "GET", "url": "/api/$dstName/$id", "headers": []},
            "response": {"status": 200, "headers": []$contentField}
        }"""
    }

    // -----------------------------------------------------------------------
    // Rich test fixtures
    //
    // Payloads used to build the typed test entries and assert against the
    // collector batch. `e1` exercises the plaintext response-body path; `e2`
    // exercises both the base64 response-body decode path and the request-body
    // capture from `postData.text`.
    // -----------------------------------------------------------------------

    private val e1ResponseBody = """{"id": 1, "status": "pending"}"""
    private val e2RequestBody = """{"total": 42.99}"""
    private val e2ResponseBody = """{"id": 2, "status": "created"}"""
    private val e2ResponseBodyBase64: String =
        Base64.getEncoder().encodeToString(e2ResponseBody.toByteArray())

    private val testEntries =
        listOf(
            // e1: HTTP GET to order-service — should be captured.
            // Response body is plaintext (encoding == null) to exercise passthrough.
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
                        content = KubesharkContent(text = e1ResponseBody),
                    ),
            ),
            // e2: HTTP POST to order-service — should be captured.
            // Carries a plaintext request body in postData (like a real Kubeshark
            // POST) and a base64-encoded response body (the default for Kubeshark
            // since it's binary-safe). The integration test verifies both the
            // request body is forwarded verbatim AND the response body is decoded
            // before reaching the collector.
            KubesharkEntry(
                id = "e2",
                timestamp = 1001,
                protocol = KubesharkProtocol(name = "http"),
                src = KubesharkEndpoint(ip = "10.0.0.1"),
                dst = KubesharkEndpoint(name = "order-service", ip = "10.0.0.2"),
                request =
                    KubesharkRequest(
                        method = "POST",
                        url = "/api/orders",
                        postData =
                            KubesharkPostData(
                                text = e2RequestBody,
                                mimeType = "application/json",
                            ),
                    ),
                response =
                    KubesharkResponse(
                        status = 201,
                        content =
                            KubesharkContent(
                                text = e2ResponseBodyBase64,
                                encoding = "base64",
                                mimeType = "application/json",
                            ),
                    ),
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

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    fun `full pipeline filters and transforms kubeshark entries to collector batch`() =
        withWiredPipeline(
            wsHandler = { index ->
                if (index != 1) {
                    delay(2_000.milliseconds)
                    return@withWiredPipeline
                }
                testEntries.forEach { send(Frame.Text(entryJson(it))) }
                delay(500.milliseconds)
            },
        ) { pipeline ->
            // Drain in a loop until we've seen both expected captured items
            // (or time out). The raw stream has 4 frames; 2 pass filters.
            val deadline = System.currentTimeMillis() + 3_000
            var capturedItems: List<CapturedInputRequest> = emptyList()
            while (capturedItems.size < 2 && System.currentTimeMillis() < deadline) {
                pipeline.captureBatch(maxWait = 200.milliseconds)
                synchronized(pipeline.collectorRequests) {
                    capturedItems = pipeline.collectorRequests.flatMap { it.items }
                }
            }

            assertEquals(2, capturedItems.size, "only e1 and e2 should pass filters")

            // e1 — GET, plaintext response body pass-through
            val item1 = capturedItems.find { it.url == "/api/orders/1" }!!
            assertEquals("GET", item1.method)
            assertEquals("svc-123", item1.serviceId)
            assertNull(item1.requestBody, "GET has no request body")
            assertEquals(
                e1ResponseBody,
                item1.responseBody,
                "plaintext response body should pass through without modification",
            )

            // e2 — POST with postData + base64 response body
            val item2 = capturedItems.find { it.url == "/api/orders" }!!
            assertEquals("POST", item2.method)
            assertEquals("svc-123", item2.serviceId)
            assertEquals(
                e2RequestBody,
                item2.requestBody,
                "request body should be captured from postData.text",
            )
            assertEquals(
                e2ResponseBody,
                item2.responseBody,
                "base64-encoded response body should be decoded before forwarding",
            )
            assertTrue(
                item2.responseBody != e2ResponseBodyBase64,
                "collector should receive decoded bytes, not the base64 ciphertext",
            )
        }

    @Test
    fun `pipeline posts nothing when no target services configured`() =
        withWiredPipeline(
            wsHandler = { index ->
                if (index != 1) {
                    delay(2_000.milliseconds)
                    return@withWiredPipeline
                }
                testEntries.forEach { send(Frame.Text(entryJson(it))) }
                delay(500.milliseconds)
            },
            dynamicConfig = DynamicConfig(targetServices = emptyMap()),
        ) { pipeline ->
            // Drain repeatedly; all entries should be filtered out by the
            // (empty) targetServices map, so the collector should never be
            // called at all.
            repeat(4) { pipeline.captureBatch(maxWait = 200.milliseconds) }

            synchronized(pipeline.collectorRequests) {
                assertEquals(
                    0,
                    pipeline.collectorRequests.size,
                    "no collector POSTs should happen when no target services match",
                )
            }
        }

    @Test
    fun `pipeline handles an idle traffic stream gracefully`(): Unit =
        withWiredPipeline(
            // Server accepts the connection, reads the KFL filter, but never
            // sends any frames — models an idle cluster.
            wsHandler = { delay(2_000.milliseconds) },
        ) { pipeline ->
            val result = pipeline.captureBatch(maxWait = 200.milliseconds)

            assertEquals(0, result.entriesProcessed)
            assertNull(result.lag)
            synchronized(pipeline.collectorRequests) {
                assertEquals(0, pipeline.collectorRequests.size)
            }
        }

    @Test
    fun `pipeline resumes capture after websocket closes and drops far-old reconnect replay`() =
        withWiredPipeline(
            wsHandler = { index ->
                when (index) {
                    1 -> {
                        // First session: send three entries, then close. After
                        // this, the agent's lastSeenTimestamp = 1_002_000 and
                        // the dedup floor = 1_002_000 - 5s = 997_000.
                        send(Frame.Text(entry("a", 1_000_000L)))
                        send(Frame.Text(entry("b", 1_001_000L)))
                        send(Frame.Text(entry("c", 1_002_000L)))
                        // Server closes by returning from the handler
                    }
                    2 -> {
                        // Reconnect session: replay an entry that's FAR older
                        // than the 5s dedup lookback window (ts=100_000, ~15
                        // minutes before lastSeen). This must be dropped by
                        // acceptAndTrack before reaching the channel. Then
                        // send a genuinely fresh entry "d" which must pass.
                        //
                        // Note: entries within the 5s lookback window (i.e.
                        // between ts=997_000 and ts=1_002_000) would slip
                        // through as accepted in-session out-of-order — that's
                        // the documented dedup trade-off, not tested here.
                        send(Frame.Text(entry("far-old", 100_000L)))
                        send(Frame.Text(entry("d", 1_003_000L)))
                        delay(500.milliseconds)
                    }
                    else -> delay(1_000.milliseconds)
                }
            },
        ) { pipeline ->
            // Drain in a loop until we see 4 distinct ids (a, b, c, d) or
            // time out. We don't assert per-batch counts because the 50ms
            // reconnect delay makes the exact drain timing racy.
            val seenIds = mutableListOf<String>()
            val deadline = System.currentTimeMillis() + 3_000
            while (seenIds.size < 4 && System.currentTimeMillis() < deadline) {
                pipeline.captureBatch(maxWait = 200.milliseconds)
                synchronized(pipeline.collectorRequests) {
                    seenIds.clear()
                    pipeline.collectorRequests.forEach { batch ->
                        batch.items.forEach { seenIds.add(it.url.substringAfterLast("/")) }
                    }
                }
            }

            // Collector should have received a, b, c, d — and crucially NOT
            // "far-old", which was dropped by the dedup lookback filter.
            assertEquals(
                listOf("a", "b", "c", "d"),
                seenIds.sorted(),
                "agent should resume capture after reconnect; far-old replay should be dropped",
            )
            assertTrue(
                "far-old" !in seenIds,
                "entries far older than the dedup lookback must be dropped on reconnect",
            )
        }

    @Test
    fun `pipeline applies backpressure when the channel is overloaded`() =
        withWiredPipeline(
            wsHandler = { index ->
                // Only the first session blasts entries; subsequent reconnects idle.
                if (index != 1) {
                    delay(2_000.milliseconds)
                    return@withWiredPipeline
                }
                // Fire 50 entries as fast as the server can push them. With
                // channelCapacity = 5, at least 45 of these will suspend the
                // streamer on channel.send until the capture loop drains.
                repeat(50) { i ->
                    send(Frame.Text(entry("evt-$i", 2_000_000L + i)))
                }
                // Hold the session open long enough for the capture loop to
                // finish draining everything
                delay(1_500.milliseconds)
            },
            channelCapacity = 5,
        ) { pipeline ->
            // Drain the channel in small batches. Each captureBatch call unblocks
            // the producer a bit. Keep going until we've seen all 50 entries or
            // we time out. A well-behaved pipeline eventually drains everything.
            val seenIds = mutableSetOf<String>()
            val deadline = System.currentTimeMillis() + 5_000
            while (seenIds.size < 50 && System.currentTimeMillis() < deadline) {
                val result = pipeline.captureBatch(batchSize = 10, maxWait = 200.milliseconds)
                synchronized(pipeline.collectorRequests) {
                    pipeline.collectorRequests.forEach { batch ->
                        batch.items.forEach { seenIds.add(it.url.substringAfterLast("/")) }
                    }
                }
                if (result.entriesProcessed == 0) delay(50.milliseconds)
            }

            // No drops: the bounded channel + TCP backpressure should preserve
            // every entry that Kubeshark sent.
            assertEquals(
                50,
                seenIds.size,
                "backpressure should not drop any entries; got $seenIds",
            )
            // Sanity: each id only appears once (no reconnect replay noise)
            assertEquals((0 until 50).map { "evt-$it" }.toSet(), seenIds)
        }

    @Test
    fun `pipeline retries transient 5xx until collector accepts the batch`() =
        withWiredPipeline(
            wsHandler = { index ->
                if (index != 1) {
                    delay(2_000.milliseconds)
                    return@withWiredPipeline
                }
                send(Frame.Text(entry("a", 3_000_000L)))
                send(Frame.Text(entry("b", 3_001_000L)))
                delay(500.milliseconds)
            },
            collectorResponder = { requestIndex ->
                // First 2 POST attempts fail with 503; the 3rd succeeds.
                // CollectorClient should retry with backoff until success.
                if (requestIndex < 3) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK
            },
        ) { pipeline ->
            delay(200.milliseconds)

            // captureOneBatch will suspend inside sendBatch while it retries
            // the 503s, then resume when the 3rd attempt succeeds. No loss.
            val result = pipeline.captureBatch(maxWait = 2_000.milliseconds)
            assertEquals(2, result.entriesProcessed)

            // Collector saw 3 POST attempts: 2 that failed transiently + 1 success
            synchronized(pipeline.collectorRequests) {
                assertEquals(
                    3,
                    pipeline.collectorRequests.size,
                    "all 3 attempts should have been POSTed",
                )
                // Every attempt carried the same batch contents (retry, not a
                // new batch). The entries must have survived the transient
                // failures — no data loss.
                pipeline.collectorRequests.forEach { batch ->
                    assertEquals(2, batch.items.size)
                    assertNotNull(batch.items.find { it.url.endsWith("/a") })
                    assertNotNull(batch.items.find { it.url.endsWith("/b") })
                }
            }
            assertEquals(
                2,
                pipeline.collectorFailureCount.get(),
                "the first two attempts should have been recorded as transient failures",
            )
        }

    @Test
    fun `config change via StateFlow triggers reconnect and filters with new target services`() =
        withWiredPipeline(
            wsHandler = { index ->
                // Each session sends one order-service entry and one api-gateway
                // entry, then holds open. The reconnect is therefore always forced
                // by the config change, not by the server closing the connection.
                val ts = index.toLong() * 10_000_000L
                send(Frame.Text(entry("os-$index", ts + 1, dstName = "order-service")))
                send(Frame.Text(entry("gw-$index", ts + 2, dstName = "api-gateway")))
                delay(5_000.milliseconds)
            },
        ) { pipeline ->
            // --- Phase 1: only order-service entries should be captured ---
            // The initial configFlow has targetServices = {"order-service": "svc-123"}.
            // Session 1 sends one order-service entry and one api-gateway entry.
            // TrafficTransformer must pass only the order-service entry.
            val deadline1 = System.currentTimeMillis() + 3_000
            var phase1Items: List<CapturedInputRequest> = emptyList()
            while (phase1Items.isEmpty() && System.currentTimeMillis() < deadline1) {
                pipeline.captureBatch(maxWait = 200.milliseconds)
                synchronized(pipeline.collectorRequests) {
                    phase1Items = pipeline.collectorRequests.flatMap { it.items }
                }
            }

            assertEquals(1, phase1Items.size, "only order-service entry should pass in phase 1")
            assertEquals("svc-123", phase1Items[0].serviceId)
            assertTrue(
                phase1Items[0].url.contains("order-service"),
                "captured URL should be from order-service",
            )

            // --- Config change: switch target services to api-gateway only ---
            // Updating configFlow triggers KubesharkClient.configWatcherJob, which
            // cancels the active session. After reconnectDelay (50ms), a new session
            // opens with the updated KFL query. TrafficTransformer also reads the
            // new config snapshot on its next call.
            val collectorCountBeforeSwitch =
                synchronized(pipeline.collectorRequests) { pipeline.collectorRequests.size }

            pipeline.configFlow.value =
                DynamicConfig(
                    targetServices = mapOf("api-gateway" to "svc-456"),
                    samplingRate = 1.0,
                )

            // --- Phase 2: only api-gateway entries should be captured ---
            // Wait for session 2 to connect and send its entries. The reconnect
            // takes ~50ms (reconnectDelay). We poll until a new batch arrives that
            // contains api-gateway entries, or until we time out.
            val deadline2 = System.currentTimeMillis() + 3_000
            var phase2Items: List<CapturedInputRequest> = emptyList()
            while (phase2Items.isEmpty() && System.currentTimeMillis() < deadline2) {
                pipeline.captureBatch(maxWait = 200.milliseconds)
                synchronized(pipeline.collectorRequests) {
                    // Only look at batches that arrived after the config change
                    val newBatches = pipeline.collectorRequests.drop(collectorCountBeforeSwitch)
                    phase2Items = newBatches.flatMap { it.items }
                }
            }

            assertEquals(1, phase2Items.size, "only api-gateway entry should pass in phase 2")
            assertEquals("svc-456", phase2Items[0].serviceId)
            assertTrue(
                phase2Items[0].url.contains("api-gateway"),
                "captured URL should be from api-gateway",
            )

            // Confirm no api-gateway entries leaked into phase 1 and no
            // order-service entries leaked into phase 2.
            assertTrue(
                phase1Items.none { it.serviceId == "svc-456" },
                "api-gateway entries must not appear in phase 1 batches",
            )
            assertTrue(
                phase2Items.none { it.serviceId == "svc-123" },
                "order-service entries must not appear in phase 2 batches",
            )
        }

    @Test
    fun `pipeline drops batch on permanent 4xx without retrying`() {
        val firstPostDone = AtomicBoolean(false)

        withWiredPipeline(
            wsHandler = { index ->
                if (index != 1) {
                    delay(2_000.milliseconds)
                    return@withWiredPipeline
                }
                // First batch: entries that will hit a 4xx and be dropped
                send(Frame.Text(entry("bad1", 4_000_000L)))
                send(Frame.Text(entry("bad2", 4_001_000L)))
                // Wait until the first POST completes before sending the
                // second batch — avoids all entries landing in one batch
                val deadline = System.currentTimeMillis() + 3_000
                while (!firstPostDone.get() && System.currentTimeMillis() < deadline) {
                    delay(10.milliseconds)
                }
                // Second batch: entries after the collector switches to 200
                send(Frame.Text(entry("ok1", 4_002_000L)))
                send(Frame.Text(entry("ok2", 4_003_000L)))
                delay(500.milliseconds)
            },
            collectorResponder = { requestIndex ->
                if (requestIndex == 1) {
                    firstPostDone.set(true)
                    HttpStatusCode.BadRequest
                } else {
                    HttpStatusCode.OK
                }
            },
        ) { pipeline ->
            // Drain until we've seen at least 2 collector POSTs, or time out.
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                pipeline.captureBatch(maxWait = 200.milliseconds)
                synchronized(pipeline.collectorRequests) {
                    if (pipeline.collectorRequests.size >= 2) break
                }
            }

            synchronized(pipeline.collectorRequests) {
                assertTrue(
                    pipeline.collectorRequests.size >= 2,
                    "expected at least 2 POSTs (1 failed + 1 success), got ${pipeline.collectorRequests.size}",
                )
                assertEquals(
                    1,
                    pipeline.collectorFailureCount.get(),
                    "the 400 should be counted exactly once (no retries)",
                )
                val successItems =
                    pipeline.collectorRequests.drop(1).flatMap { it.items }
                assertNotNull(
                    successItems.find { it.url.endsWith("/ok1") },
                    "ok1 should be in a successful batch",
                )
                assertNotNull(
                    successItems.find { it.url.endsWith("/ok2") },
                    "ok2 should be in a successful batch",
                )
            }
        }
    }
}
