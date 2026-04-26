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
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
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
 * ## Why this file used to be flaky (and why it isn't anymore)
 *
 * The previous design polled the collector's request list on a deadline:
 *
 * ```
 * while (deadline not reached) { captureBatch(maxWait=200ms); check count }
 * ```
 *
 * That depends on real network I/O between an embedded Ktor WS server and the
 * Ktor CIO client. Batch composition is therefore non-deterministic: if both
 * `a` and `b` are in the channel when `drainBatch` runs, you get one batch;
 * if only `a` arrived in time, you get two batches. Tests that asserted exact
 * POST counts (e.g. "expected 3, got 4") flaked under slow CI runners.
 *
 * Increasing deadlines didn't fix this — the race is structural.
 *
 * ## How this file is now structured
 *
 * 1. **Signal-based synchronization.** The collector mock emits a
 *    [CollectorEvent] to a [MutableSharedFlow] for every POST. Tests subscribe
 *    via [WiredPipeline.collectorEvents] and `await` specific events.
 *    [withTimeout] is a hang detector, NOT a wall-clock guess.
 * 2. **Continuous capture loop.** A background coroutine runs
 *    [captureOneBatch] in a tight loop, mirroring production. Tests don't
 *    drive the loop; they observe its outputs.
 * 3. **Batch-agnostic assertions.** Tests assert *intent* ("retry observed",
 *    "no data loss") rather than *implementation* ("exactly 3 POSTs"). Tests
 *    that genuinely need a specific batch count must pin batching upstream
 *    (e.g. by waiting for an event before sending more entries).
 * 4. **WS handlers hold sessions open with [awaitCancellation].** Sessions
 *    end when the test scope ends. Tests that explicitly want the server to
 *    close the connection (to exercise reconnect) just `return` from the
 *    handler.
 *
 * The `ConfigClient` and service-discovery loops are intentionally out of
 * scope here — they're tested in isolation in [LoopLogicTest]. This file
 * focuses on the data path from Kubeshark to the collector.
 */
class CapturePipelineIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    // -----------------------------------------------------------------------
    // Test harness
    // -----------------------------------------------------------------------

    /**
     * One observation of the collector being called. Tests assert against
     * sequences of these via [WiredPipeline.collectorEvents].
     */
    private data class CollectorEvent(
        val requestIndex: Int,
        val batch: BatchCapturedInputRequest,
        val responseStatus: HttpStatusCode,
    )

    /**
     * A fully-wired pipeline: real [KubesharkClient] connected to an embedded
     * Ktor WebSocket server, real [CollectorClient] backed by a Ktor [MockEngine]
     * whose responses the test scripts. A background capture loop drains the
     * pipeline continuously — tests subscribe to [collectorEvents] for signals.
     */
    private class WiredPipeline(
        val configFlow: MutableStateFlow<DynamicConfig>,
        val collectorEvents: SharedFlow<CollectorEvent>,
    ) {
        /**
         * Wait until [count] collector POSTs have been observed.
         * [timeout] is a hang detector — if the pipeline genuinely produces
         * [count] events, this returns quickly. Default 30s tolerates slow CI.
         */
        suspend fun awaitCollectorRequests(
            count: Int,
            timeout: Duration = 30.seconds,
        ): List<CollectorEvent> = withTimeout(timeout) { collectorEvents.take(count).toList() }

        /**
         * Wait until every URL suffix in [expectedUrlSuffixes] has appeared in
         * a **successful** collector batch (2xx response). Returns ALL events
         * accumulated up to (and including) the one that completed the set —
         * including any earlier failures, so retry-behaviour assertions can
         * inspect the full attempt history.
         *
         * Use this when the test cares about *what was delivered* but not
         * about how it was batched. Robust to batch-composition variability.
         * Counting only successful events is what makes this robust to retries:
         * the same items may appear in multiple failed attempts before being
         * accepted, and we only consider the delivery complete when the
         * collector ACKs.
         */
        suspend fun awaitEntriesDelivered(
            expectedUrlSuffixes: Set<String>,
            timeout: Duration = 30.seconds,
        ): List<CollectorEvent> {
            val accumulated = mutableListOf<CollectorEvent>()
            val seen = mutableSetOf<String>()
            withTimeout(timeout) {
                collectorEvents
                    .takeWhile { event ->
                        accumulated.add(event)
                        if (event.responseStatus.isSuccess()) {
                            event.batch.items.forEach { item ->
                                expectedUrlSuffixes
                                    .firstOrNull { suffix -> item.url.endsWith(suffix) }
                                    ?.let(seen::add)
                            }
                        }
                        seen != expectedUrlSuffixes
                    }.toList()
            }
            return accumulated
        }

        /**
         * Wait until the cumulative item count across all batches is at least
         * [expectedTotalItems]. Returns events accumulated so far.
         */
        suspend fun awaitTotalItems(
            expectedTotalItems: Int,
            timeout: Duration = 30.seconds,
        ): List<CollectorEvent> {
            val accumulated = mutableListOf<CollectorEvent>()
            var total = 0
            withTimeout(timeout) {
                collectorEvents
                    .takeWhile { event ->
                        accumulated.add(event)
                        total += event.batch.items.size
                        total < expectedTotalItems
                    }.toList()
            }
            return accumulated
        }

        /**
         * Wait for the first event matching [predicate].
         * [timeout] is a hang detector.
         */
        suspend fun awaitEvent(
            timeout: Duration = 30.seconds,
            predicate: suspend (CollectorEvent) -> Boolean,
        ): CollectorEvent = withTimeout(timeout) { collectorEvents.first(predicate) }

        /**
         * Assert that no NEW collector events arrive in the next [window].
         *
         * Used for "negative" assertions like "the dropped batch was not
         * retried" or "no entries were forwarded when no targets configured."
         * Bounded time-based check is unavoidable for proving absence — the
         * window is generous (default 500ms) so a slow pipeline that *was*
         * about to fire would still be caught.
         */
        suspend fun assertNoMoreEventsFor(
            window: Duration = 500.milliseconds,
            since: Int = collectorEvents.replayCache.size,
        ) {
            delay(window)
            val current = collectorEvents.replayCache.size
            assertEquals(
                since,
                current,
                "expected no new collector events in $window, but ${current - since} arrived",
            )
        }
    }

    /**
     * Spin up an embedded Ktor server with a `/api/wsFull` handler, a real
     * [KubesharkClient] connected to it, a real [CollectorClient] backed by a
     * MockEngine, and a background capture loop. Invoke [block] with the
     * resulting [WiredPipeline]; tear everything down on exit.
     *
     * @param wsHandler Called per WebSocket session. The KFL-filter frame has
     *   already been consumed before this is invoked. Hold the session open
     *   with [awaitCancellation]; close it by simply returning.
     * @param channelCapacity Small values force backpressure.
     * @param reconnectDelay Short values make reconnect tests fast.
     * @param collectorResponder Returns the status the collector responds with
     *   for the n-th request (1-indexed).
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
        block: suspend WiredPipeline.() -> Unit,
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

        val wsHttpClient = buildAgentKubesharkHttpClient()

        // SharedFlow for collector observations. replay = Int.MAX_VALUE so a
        // late subscriber sees the full history (tests sometimes assert on
        // the past via replayCache).
        val collectorEventsFlow = MutableSharedFlow<CollectorEvent>(replay = Int.MAX_VALUE)
        val collectorRequestCounter = AtomicInteger(0)
        val collectorEngine =
            MockEngine { request ->
                when {
                    request.url.encodedPath.contains("/api/captured-inputs") -> {
                        val bodyString = request.bodyAsDecodedString()
                        val batch = json.decodeFromString<BatchCapturedInputRequest>(bodyString)
                        val index = collectorRequestCounter.incrementAndGet()
                        val status = collectorResponder(index)
                        collectorEventsFlow.tryEmit(CollectorEvent(index, batch, status))
                        respondJson("""{"accepted": ${status.isSuccess()}}""", status)
                    }
                    else -> respondJson("{}")
                }
            }
        val collectorHttpClient = buildAgentCollectorHttpClient(collectorEngine)

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

            // Background capture loop — drains the pipeline continuously, just
            // like production's CapturePipeline. Tests observe its effects via
            // [collectorEventsFlow]; they don't drive it directly.
            clientScope.launch {
                while (isActive) {
                    try {
                        captureOneBatch(
                            batchSize = 100,
                            maxWait = 50.milliseconds,
                            kubesharkClient = kubesharkClient,
                            collectorClient = collectorClient,
                            transformer = transformer,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Production captureLoop also tolerates transient errors
                    }
                }
            }

            val pipeline =
                WiredPipeline(
                    configFlow = configFlow,
                    collectorEvents = collectorEventsFlow.asSharedFlow(),
                )
            pipeline.block()
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

    private fun entryJson(entry: KubesharkEntry): String = json.encodeToString(KubesharkEntry.serializer(), entry)

    /** Build a minimal HTTP GET entry as a JSON frame. */
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

    private val e1ResponseBody = """{"id": 1, "status": "pending"}"""
    private val e2RequestBody = """{"total": 42.99}"""
    private val e2ResponseBody = """{"id": 2, "status": "created"}"""
    private val e2ResponseBodyBase64: String =
        Base64.getEncoder().encodeToString(e2ResponseBody.toByteArray())

    private val testEntries =
        listOf(
            // e1: HTTP GET to order-service — captured. Plaintext response body.
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
            // e2: HTTP POST to order-service — captured. Tests request body
            // capture and base64 response decode.
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
            // e3: filtered out — wrong service
            KubesharkEntry(
                id = "e3",
                timestamp = 1002,
                protocol = KubesharkProtocol(name = "http"),
                src = KubesharkEndpoint(ip = "10.0.0.1"),
                dst = KubesharkEndpoint(name = "unknown-service", ip = "10.0.0.3"),
                request = KubesharkRequest(method = "GET", url = "/health"),
                response = KubesharkResponse(status = 200),
            ),
            // e4: filtered out — wrong protocol
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
                if (index == 1) {
                    testEntries.forEach { send(Frame.Text(entryJson(it))) }
                }
                awaitCancellation()
            },
        ) {
            // Wait until both expected URLs have been delivered (regardless of
            // batching). e1 → /api/orders/1, e2 → /api/orders. e3 and e4 are
            // filtered out and should never appear.
            val events = awaitEntriesDelivered(setOf("/api/orders/1", "/api/orders"))
            val capturedItems =
                events
                    .flatMap { it.batch.items }
                    .distinctBy { it.url }

            assertEquals(2, capturedItems.size, "only e1 and e2 should pass filters")

            val item1 = capturedItems.find { it.url == "/api/orders/1" }!!
            assertEquals("GET", item1.method)
            assertEquals("svc-123", item1.serviceId)
            assertNull(item1.requestBody, "GET has no request body")
            assertEquals(
                e1ResponseBody,
                item1.responseBody,
                "plaintext response body should pass through without modification",
            )

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
                if (index == 1) {
                    testEntries.forEach { send(Frame.Text(entryJson(it))) }
                }
                awaitCancellation()
            },
            dynamicConfig = DynamicConfig(targetServices = emptyMap()),
        ) {
            // The transformer filters everything out (no targets match), so no
            // collector POSTs should ever happen. assertNoMoreEventsFor uses a
            // bounded settle window — generous enough to catch a slow pipeline.
            assertNoMoreEventsFor(window = 1.seconds, since = 0)
        }

    @Test
    fun `pipeline handles an idle traffic stream gracefully`() =
        withWiredPipeline(
            // Server accepts the connection, reads the KFL filter, never sends
            // any frames — models an idle cluster.
            wsHandler = { awaitCancellation() },
        ) {
            assertNoMoreEventsFor(window = 1.seconds, since = 0)
        }

    @Test
    fun `pipeline resumes capture after websocket closes and drops far-old reconnect replay`() =
        withWiredPipeline(
            wsHandler = { index ->
                when (index) {
                    1 -> {
                        // First session: send three entries, then close (return)
                        // to trigger reconnect. After this, lastSeenTimestamp
                        // = 1_002_000 and the dedup floor = 1_002_000 - 5s.
                        send(Frame.Text(entry("a", 1_000_000L)))
                        send(Frame.Text(entry("b", 1_001_000L)))
                        send(Frame.Text(entry("c", 1_002_000L)))
                        // returning closes the session
                    }
                    2 -> {
                        // Reconnect: send a far-old entry that must be dropped
                        // (older than 5s lookback), then a fresh entry "d"
                        // that must pass.
                        send(Frame.Text(entry("far-old", 100_000L)))
                        send(Frame.Text(entry("d", 1_003_000L)))
                        awaitCancellation()
                    }
                    else -> awaitCancellation()
                }
            },
        ) {
            // Wait until a, b, c, d have all been delivered.
            val events = awaitEntriesDelivered(setOf("/a", "/b", "/c", "/d"))
            val deliveredIds =
                events
                    .flatMap { it.batch.items }
                    .map { it.url.substringAfterLast("/") }
                    .toSet()

            assertEquals(setOf("a", "b", "c", "d"), deliveredIds)
            assertTrue(
                "far-old" !in deliveredIds,
                "entries far older than the dedup lookback must be dropped on reconnect",
            )
        }

    @Test
    fun `pipeline applies backpressure when the channel is overloaded`() =
        withWiredPipeline(
            wsHandler = { index ->
                if (index == 1) {
                    // Fire 50 entries as fast as the server can push them.
                    // With channelCapacity=5, ~45 of these will suspend on
                    // channel.send until the capture loop drains.
                    repeat(50) { i ->
                        send(Frame.Text(entry("evt-$i", 2_000_000L + i)))
                    }
                }
                awaitCancellation()
            },
            channelCapacity = 5,
        ) {
            // Wait until all 50 items have been delivered (across however many
            // batches the capture loop produces). No drops: the bounded
            // channel + TCP backpressure should preserve every entry.
            val events = awaitTotalItems(50)
            val seenIds =
                events
                    .flatMap { it.batch.items }
                    .map { it.url.substringAfterLast("/") }
                    .toSet()

            assertEquals(
                (0 until 50).map { "evt-$it" }.toSet(),
                seenIds,
                "backpressure should not drop any entries",
            )
        }

    @Test
    fun `pipeline retries transient 5xx until collector accepts the batch`() =
        withWiredPipeline(
            wsHandler = { index ->
                if (index == 1) {
                    send(Frame.Text(entry("a", 3_000_000L)))
                    send(Frame.Text(entry("b", 3_001_000L)))
                }
                awaitCancellation()
            },
            collectorResponder = { requestIndex ->
                // Fail the first 2 POST attempts (regardless of content),
                // then succeed. We assert retry behavior intentionally: any
                // batch that gets a 5xx must be re-POSTed until success.
                if (requestIndex < 3) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK
            },
        ) {
            // Wait until both entries have been delivered to the collector
            // (in *some* successful batch). Robust to batching variability.
            val events = awaitEntriesDelivered(setOf("/a", "/b"))

            // Intent #1: every distinct batch content was eventually accepted.
            // For each unique batch payload, the LAST attempt with that
            // content must have succeeded — proving retry-until-success.
            val byContent =
                events.groupBy { evt ->
                    evt.batch.items
                        .map { it.url }
                        .toSet()
                }
            byContent.forEach { (content, evts) ->
                val finalAttempt = evts.maxByOrNull { it.requestIndex }!!
                assertTrue(
                    finalAttempt.responseStatus.isSuccess(),
                    "batch $content should eventually succeed; got ${evts.map { it.responseStatus }}",
                )
            }

            // Intent #2: at least one transient failure was actually observed
            // (otherwise the test isn't exercising what it claims to).
            assertTrue(
                events.any { !it.responseStatus.isSuccess() },
                "test should have observed at least one transient 5xx",
            )

            // Intent #3: no data loss. Union of all successful batches covers
            // both /a and /b.
            val deliveredUrls =
                events
                    .filter { it.responseStatus.isSuccess() }
                    .flatMap { it.batch.items.map { item -> item.url } }
                    .toSet()
            assertTrue(deliveredUrls.any { it.endsWith("/a") }, "a should have been delivered")
            assertTrue(deliveredUrls.any { it.endsWith("/b") }, "b should have been delivered")
        }

    @Test
    fun `config change via StateFlow triggers reconnect and filters with new target services`() =
        withWiredPipeline(
            wsHandler = { index ->
                // Each session sends one order-service entry and one api-gateway
                // entry, then holds open. Reconnect is forced by the config
                // change (not by the server closing the connection).
                val ts = index.toLong() * 10_000_000L
                send(Frame.Text(entry("os-$index", ts + 1, dstName = "order-service")))
                send(Frame.Text(entry("gw-$index", ts + 2, dstName = "api-gateway")))
                awaitCancellation()
            },
        ) {
            // Phase 1: wait until an order-service URL appears. The transformer
            // should filter out api-gateway because the initial config only
            // targets order-service.
            val phase1Event =
                awaitEvent { event ->
                    event.batch.items.any { it.url.contains("order-service") }
                }
            val phase1Items = phase1Event.batch.items
            assertEquals(1, phase1Items.size, "only order-service entry should pass in phase 1")
            assertEquals("svc-123", phase1Items[0].serviceId)

            // Snapshot how many events we've seen so we can compare phase 2 events
            // against only what arrives AFTER the config change.
            val eventCountBeforeSwitch = collectorEvents.replayCache.size

            // Switch targets: KubesharkClient.configWatcherJob will cancel the
            // active session and reconnect with the new KFL query.
            configFlow.value =
                DynamicConfig(
                    targetServices = mapOf("api-gateway" to "svc-456"),
                    samplingRate = 1.0,
                )

            // Phase 2: wait for an api-gateway URL to land in some POST that
            // happened AFTER the config change.
            val phase2Event =
                awaitEvent { event ->
                    event.requestIndex > eventCountBeforeSwitch &&
                        event.batch.items.any { it.url.contains("api-gateway") }
                }
            val phase2Items = phase2Event.batch.items
            assertEquals(1, phase2Items.size, "only api-gateway entry should pass in phase 2")
            assertEquals("svc-456", phase2Items[0].serviceId)

            // Cross-check leakage: no api-gateway items should have appeared
            // before the switch, and no order-service items after.
            val pre =
                collectorEvents.replayCache
                    .take(eventCountBeforeSwitch)
                    .flatMap { it.batch.items }
            val post =
                collectorEvents.replayCache
                    .drop(eventCountBeforeSwitch)
                    .flatMap { it.batch.items }
            assertTrue(
                pre.none { it.serviceId == "svc-456" },
                "api-gateway entries must not appear in phase 1 batches",
            )
            assertTrue(
                post.none { it.serviceId == "svc-123" },
                "order-service entries must not appear in phase 2 batches",
            )
        }

    @Test
    fun `pipeline drops batch on permanent 4xx without retrying`() =
        withWiredPipeline(
            wsHandler = { index ->
                if (index == 1) {
                    // bad1, bad2 will hit a 4xx and be dropped. The retry
                    // assertion is what matters here — no need to send a
                    // success batch after; the rest of the suite covers
                    // "pipeline keeps working." This test focuses on the
                    // single invariant: 4xx is not retried.
                    send(Frame.Text(entry("bad1", 4_000_000L)))
                    send(Frame.Text(entry("bad2", 4_001_000L)))
                }
                awaitCancellation()
            },
            collectorResponder = { requestIndex ->
                // First POST attempt always fails with 400; everything after
                // succeeds. The agent must NOT retry the 400.
                if (requestIndex == 1) HttpStatusCode.BadRequest else HttpStatusCode.OK
            },
        ) {
            // Wait for the 4xx to be observed.
            val failedEvent = awaitEvent { !it.responseStatus.isSuccess() }
            val droppedItemUrls =
                failedEvent.batch.items
                    .map { it.url }
                    .toSet()

            // Settle window: give any potential retry a chance to fire
            // (CollectorClient backoff is 5-50ms; 500ms is generous). We
            // can't use assertNoMoreEventsFor here because bad1 and bad2
            // might arrive in separate batches — the second batch with the
            // remaining item will also be POSTed (and succeed). That's not
            // a retry; it's a different batch. Only items whose URLs match
            // the dropped batch count as a retry.
            delay(500.milliseconds)

            // Strict invariant: items from the dropped batch must never
            // appear in any subsequent collector POST. Batch-agnostic —
            // works whether [bad1, bad2] arrived together or split.
            val droppedItemReoccurrences =
                collectorEvents.replayCache
                    .filter { it.requestIndex > failedEvent.requestIndex }
                    .flatMap { it.batch.items }
                    .map { it.url }
                    .count { it in droppedItemUrls }
            assertEquals(
                0,
                droppedItemReoccurrences,
                "dropped batch items must NOT be retried — saw $droppedItemReoccurrences re-occurrence(s)",
            )
        }
}
