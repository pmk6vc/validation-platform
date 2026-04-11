package com.platform.agent

import com.platform.agent.models.BatchCapturedInputRequest
import com.platform.agent.models.KubesharkContent
import com.platform.agent.models.KubesharkEndpoint
import com.platform.agent.models.KubesharkEntry
import com.platform.agent.models.KubesharkHeader
import com.platform.agent.models.KubesharkPostData
import com.platform.agent.models.KubesharkProtocol
import com.platform.agent.models.KubesharkRequest
import com.platform.agent.models.KubesharkResponse
import io.ktor.client.HttpClient
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
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Integration test wiring real components with mock backends.
 * Verifies the full pipeline: Kubeshark entries → transformer → collector POST.
 */
class AgentIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Build a mock KubesharkClient whose drainBatch returns the given entries, capped at `limit`. */
    private fun fakeKubesharkClient(entries: List<KubesharkEntry>): KubesharkClient {
        val client = mockk<KubesharkClient>()
        coEvery { client.drainBatch(any<Int>(), any<Duration>()) } answers {
            val limit = firstArg<Int>()
            entries.take(limit)
        }
        return client
    }

    // Payloads used to build the test entries and assert against the collector batch.
    // e1 exercises the plaintext response-body path; e2 exercises both the base64
    // response-body decode path and the request-body capture from `postData.text`.
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

            val kubesharkClient = fakeKubesharkClient(testEntries)
            val collectorClient =
                CollectorClient(httpClient, "http://collector:8081", "key")
            val transformer = TrafficTransformer(dynamicConfig)

            val result =
                captureOneBatch(
                    batchSize = 100,
                    maxWait = 1.seconds,
                    kubesharkClient = kubesharkClient,
                    collectorClient = collectorClient,
                    transformer = transformer,
                )

            // All 4 raw entries drained; 2 passed filters and went to collector
            assertEquals(4, result.entriesProcessed)

            // Collector received exactly one batch POST
            assertEquals(1, collectorRequestCount)

            // Verify the batch body has 2 items (e1 + e2, filtered e3 + e4)
            val batch =
                json.decodeFromString<BatchCapturedInputRequest>(collectorRequestBody)
            assertEquals(2, batch.items.size)

            // e1 — GET request, plaintext response body should pass through verbatim
            val item1 = batch.items[0]
            assertEquals("GET", item1.method)
            assertEquals("/api/orders/1", item1.url)
            assertEquals("svc-123", item1.serviceId)
            assertNull(item1.requestBody, "GET has no request body")
            assertEquals(
                e1ResponseBody,
                item1.responseBody,
                "plaintext response body should pass through without modification",
            )

            // e2 — POST with request body AND base64-encoded response body.
            // The transformer must capture postData.text verbatim AND decode
            // the base64 response content before the collector sees it.
            val item2 = batch.items[1]
            assertEquals("POST", item2.method)
            assertEquals("/api/orders", item2.url)
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
            // Sanity check: the raw base64 string must NOT leak through to the collector
            assertTrue(
                item2.responseBody != e2ResponseBodyBase64,
                "collector should receive decoded bytes, not the base64 ciphertext",
            )
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

            val kubesharkClient = fakeKubesharkClient(testEntries)
            val collectorClient =
                CollectorClient(httpClient, "http://collector:8081", "key")
            val transformer = TrafficTransformer(dynamicConfig)

            val result =
                captureOneBatch(
                    batchSize = 100,
                    maxWait = 1.seconds,
                    kubesharkClient = kubesharkClient,
                    collectorClient = collectorClient,
                    transformer = transformer,
                )

            // All 4 raw entries drained, none matched, nothing sent
            assertEquals(4, result.entriesProcessed)
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

            val kubesharkClient = fakeKubesharkClient(emptyList())
            val collectorClient =
                CollectorClient(httpClient, "http://collector:8081", "key")
            val transformer = TrafficTransformer(dynamicConfig)

            val result =
                captureOneBatch(
                    batchSize = 100,
                    maxWait = 1.seconds,
                    kubesharkClient = kubesharkClient,
                    collectorClient = collectorClient,
                    transformer = transformer,
                )

            assertEquals(0, result.entriesProcessed)
            assertNull(result.lag)
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
    // Failure-mode tests
    //
    // Unlike the three tests above (which use a `mockk<KubesharkClient>` to
    // exercise pipeline logic cheaply), the tests below wire up the REAL
    // KubesharkClient against an embedded Ktor WebSocket server. This gives
    // us real transport, real backpressure, real reconnect, and real error
    // handling — the things we care about when testing failure modes.
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
                install(io.ktor.server.websocket.WebSockets)
                routing {
                    webSocket("/api/wsFull") {
                        // Consume the KFL filter frame (empty string from our client)
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
            HttpClient(io.ktor.client.engine.cio.CIO) {
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

        try {
            val kubesharkClient =
                KubesharkClient(
                    httpClient = wsHttpClient,
                    baseUrl = "http://127.0.0.1:$port",
                    scope = this,
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
            val transformer = TrafficTransformer(AtomicReference(dynamicConfig))

            val pipeline =
                WiredPipeline(
                    kubesharkClient = kubesharkClient,
                    collectorClient = collectorClient,
                    transformer = transformer,
                    collectorRequests = collectorRequests,
                    collectorFailureCount = collectorFailureCount,
                )
            block(pipeline)
        } finally {
            // Cancelling the enclosing scope would be cleaner but `runBlocking`
            // does that for us at exit. We just need to close HTTP clients and
            // stop the server.
            wsHttpClient.close()
            collectorHttpClient.close()
            server.stop(100, 100)
        }
    }

    /**
     * Helper: craft a test HTTP entry that the default target-services map
     * ("order-service" → "svc-123") will match.
     */
    private fun entry(
        id: String,
        timestamp: Long,
        body: String? = null,
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
            "dst": {"ip": "10.0.0.2", "port": "8080", "name": "order-service", "namespace": "production"},
            "request": {"method": "GET", "url": "/api/orders/$id", "headers": []},
            "response": {"status": 200, "headers": []$contentField}
        }"""
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
                        delay(500)
                    }
                    else -> delay(1_000)
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
                    delay(2_000)
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
                delay(1_500)
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
                if (result.entriesProcessed == 0) delay(50)
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
                    delay(2_000)
                    return@withWiredPipeline
                }
                send(Frame.Text(entry("a", 3_000_000L)))
                send(Frame.Text(entry("b", 3_001_000L)))
                delay(500)
            },
            collectorResponder = { requestIndex ->
                // First 2 POST attempts fail with 503; the 3rd succeeds.
                // CollectorClient should retry with backoff until success.
                if (requestIndex < 3) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK
            },
        ) { pipeline ->
            delay(200)

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
    fun `pipeline drops batch on permanent 4xx without retrying`() =
        withWiredPipeline(
            wsHandler = { index ->
                if (index != 1) {
                    delay(2_000)
                    return@withWiredPipeline
                }
                // First batch: 2 entries that will hit a 4xx and be dropped
                send(Frame.Text(entry("bad1", 4_000_000L)))
                send(Frame.Text(entry("bad2", 4_001_000L)))
                delay(300)
                // Second batch: 2 entries after the server switches to 200
                send(Frame.Text(entry("ok1", 4_002_000L)))
                send(Frame.Text(entry("ok2", 4_003_000L)))
                delay(500)
            },
            collectorResponder = { requestIndex ->
                // First POST gets a 400 (e.g., schema mismatch); subsequent OK
                if (requestIndex == 1) HttpStatusCode.BadRequest else HttpStatusCode.OK
            },
        ) { pipeline ->
            delay(200)

            // First batch — collector returns 400. CollectorClient treats 4xx
            // as permanent (retrying won't help a malformed request). The
            // batch is dropped, but sendBatch returns normally — no exception,
            // no backpressure, capture loop continues.
            val first = pipeline.captureBatch(maxWait = 500.milliseconds)
            assertEquals(2, first.entriesProcessed)
            assertEquals(1, pipeline.collectorFailureCount.get(), "the 400 should be counted once")

            delay(400)

            // Second batch — collector accepts. Agent is still working; the
            // previous 4xx did not crash anything or block subsequent batches.
            val second = pipeline.captureBatch(maxWait = 500.milliseconds)
            assertEquals(2, second.entriesProcessed)

            synchronized(pipeline.collectorRequests) {
                // Exactly 2 POST attempts: the 400'd attempt (which was NOT
                // retried) and the successful second-batch attempt.
                assertEquals(
                    2,
                    pipeline.collectorRequests.size,
                    "4xx should not trigger retries",
                )
                val secondBatchItems = pipeline.collectorRequests[1].items
                assertEquals(2, secondBatchItems.size)
                assertNotNull(secondBatchItems.find { it.url.endsWith("/ok1") })
                assertNotNull(secondBatchItems.find { it.url.endsWith("/ok2") })
            }
        }
}
