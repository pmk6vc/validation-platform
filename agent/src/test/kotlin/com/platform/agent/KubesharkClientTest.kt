package com.platform.agent

import com.platform.agent.models.KubesharkEntry
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/**
 * Tests for [KubesharkClient]'s persistent WebSocket session, drainBatch API,
 * StateFlow-driven KFL reconnect, and [KubesharkClient.buildKflQuery].
 *
 * Each transport test spins up an embedded Ktor Netty server on a random port
 * that implements a fake `/api/wsFull` handler. The real [KubesharkClient]
 * connects to it over a real WebSocket, so we're exercising the full transport
 * stack (Ktor CIO client → TCP → Ktor Netty server) without mocking.
 *
 * ## Synchronization approach
 *
 * All timing-sensitive synchronization is signal-based:
 *
 * - WS server handlers hold sessions open with [awaitCancellation] rather than
 *   `delay(N)`. Sessions end when the test scope ends; handlers that
 *   intentionally want to close the connection (to exercise reconnect) just
 *   `return`.
 * - The server reports KFL filter strings over a [Channel] so test bodies can
 *   `await` specific connection events without polling.
 * - [withTimeout] is a hang detector, not a wall-clock guess. A test that would
 *   pass on a fast machine also passes on a slow CI runner.
 * - [drainBatch]'s own suspension provides the signal for entry arrival — tests
 *   call it directly with a generous [withTimeout] rather than spinning in a
 *   poll loop.
 */
class KubesharkClientTest {
    private fun wsEntry(
        id: String,
        timestamp: Long,
        method: String = "GET",
        url: String = "/api/test",
        status: Int = 200,
        dstName: String = "order-service",
    ): String =
        """{
        "id": "$id",
        "timestamp": $timestamp,
        "protocol": {"name": "http", "abbr": "HTTP"},
        "tls": false,
        "src": {"ip": "10.0.0.1", "port": "45678", "name": "client-pod", "namespace": "production"},
        "dst": {"ip": "10.0.0.2", "port": "8080", "name": "$dstName", "namespace": "production"},
        "request": {"method": "$method", "url": "$url", "headers": []},
        "response": {"status": $status, "headers": []},
        "requestSize": 100,
        "responseSize": 200,
        "elapsedTime": 50
    }"""

    /**
     * Wires up a fake WS server and a [KubesharkClient] connected to it.
     *
     * @param serverBlock Called per WebSocket session after the KFL filter frame
     *   is consumed. Hold the session open with [awaitCancellation]; returning
     *   closes the connection (triggers reconnect).
     * @param filterChannel Receives the KFL filter string for every new
     *   connection. Tests `receive()` from this channel (with [withTimeout]) to
     *   await connection events without polling.
     */
    private fun withClient(
        serverBlock: suspend DefaultWebSocketServerSession.() -> Unit,
        testBlock: suspend CoroutineScope.(KubesharkClient, MutableStateFlow<DynamicConfig>) -> Unit,
        configFlow: MutableStateFlow<DynamicConfig> = MutableStateFlow(DynamicConfig.default()),
        reconnectDelay: Duration = 50.milliseconds,
        filterChannel: Channel<String> = Channel(capacity = Channel.UNLIMITED),
    ) = runBlocking {
        val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
            embeddedServer(Netty, port = 0) {
                install(ServerWebSockets)
                routing {
                    webSocket("/api/wsFull") {
                        val filter = (incoming.receive() as Frame.Text).readText()
                        filterChannel.send(filter)
                        serverBlock()
                    }
                }
            }
        server.start(wait = false)
        val port =
            server.engine
                .resolvedConnectors()
                .first()
                .port

        val httpClient = buildAgentKubesharkHttpClient()

        val clientScope = CoroutineScope(coroutineContext + Job())
        try {
            val client =
                KubesharkClient(
                    httpClient = httpClient,
                    baseUrl = "http://127.0.0.1:$port",
                    scope = clientScope,
                    configFlow = configFlow,
                    reconnectDelay = reconnectDelay,
                )
            testBlock(client, configFlow)
        } finally {
            clientScope.cancel()
            httpClient.close()
            server.stop(100, 100)
        }
    }

    /**
     * Await [count] KFL filter strings from [filterChannel], returning them as
     * a list in arrival order. [withTimeout] is the hang detector.
     */
    private suspend fun awaitFilters(
        filterChannel: Channel<String>,
        count: Int,
        timeout: Duration = 30.seconds,
    ): List<String> =
        withTimeout(timeout) {
            List(count) { filterChannel.receive() }
        }

    /**
     * Drain exactly [count] entries by calling [drainBatch] in a loop, waiting
     * for each batch with [withTimeout]. Returns when [count] entries have been
     * collected. Signal-based: suspends until entries actually arrive.
     */
    private suspend fun drainUntil(
        client: KubesharkClient,
        count: Int,
        timeout: Duration = 30.seconds,
    ): List<KubesharkEntry> =
        withTimeout(timeout) {
            val entries = mutableListOf<KubesharkEntry>()
            while (entries.size < count) {
                entries.addAll(client.drainBatch(limit = count - entries.size, maxWait = 5.seconds))
            }
            entries
        }

    // -----------------------------------------------------------------------
    // KFL filter transmission — assert what the server actually receives
    // -----------------------------------------------------------------------

    @Test
    fun `sends http-only KFL filter when no target services configured`() {
        val filterChannel = Channel<String>(capacity = Channel.UNLIMITED)
        withClient(
            serverBlock = { awaitCancellation() },
            testBlock = { _, _ ->
                val filters = awaitFilters(filterChannel, 1)
                assertEquals(listOf("http"), filters)
            },
            filterChannel = filterChannel,
        )
    }

    @Test
    fun `sends KFL query with target services on connect`() {
        val filterChannel = Channel<String>(capacity = Channel.UNLIMITED)
        val config =
            MutableStateFlow(
                DynamicConfig(targetServices = mapOf("order-service" to "svc-1")),
            )
        withClient(
            serverBlock = { awaitCancellation() },
            testBlock = { _, _ ->
                val filters = awaitFilters(filterChannel, 1)
                assertEquals(
                    listOf("""http and dst.name == "order-service""""),
                    filters,
                )
            },
            configFlow = config,
            filterChannel = filterChannel,
        )
    }

    @Test
    fun `same targetServices update does not trigger reconnect`() {
        val filterChannel = Channel<String>(capacity = Channel.UNLIMITED)
        val config =
            MutableStateFlow(
                DynamicConfig(targetServices = mapOf("order-service" to "svc-1")),
            )

        withClient(
            serverBlock = { awaitCancellation() },
            testBlock = { _, flow ->
                // Wait for the initial connection to be established.
                awaitFilters(filterChannel, 1)

                // Update with same targetServices but different samplingRate.
                // configWatcherJob only reconnects when targetServices changes.
                flow.value =
                    DynamicConfig(
                        targetServices = mapOf("order-service" to "svc-1"),
                        samplingRate = 0.5,
                    )

                // No second connection should ever arrive — verify by attempting
                // to receive one and expecting a timeout. withTimeoutOrNull
                // returns null cleanly on timeout. (withTimeout would throw
                // TimeoutCancellationException, which propagates past
                // runCatching since it's a cancellation exception.) 500ms is
                // generous enough to catch a spurious reconnect.
                val secondFilter =
                    withTimeoutOrNull(500.milliseconds) {
                        filterChannel.receive()
                    }
                assertEquals(null, secondFilter, "same targetServices must not trigger reconnect")
            },
            configFlow = config,
            filterChannel = filterChannel,
        )
    }

    @Test
    fun `targetServices change forces immediate reconnect`() {
        val filterChannel = Channel<String>(capacity = Channel.UNLIMITED)
        val config =
            MutableStateFlow(
                DynamicConfig(targetServices = mapOf("order-service" to "svc-1")),
            )

        withClient(
            serverBlock = { awaitCancellation() },
            testBlock = { _, flow ->
                // Wait for the initial connection.
                awaitFilters(filterChannel, 1)

                // Change targetServices — this should trigger an immediate reconnect.
                flow.value =
                    DynamicConfig(
                        targetServices = mapOf("api-gateway" to "svc-2"),
                    )

                // Await the second connection's KFL filter.
                val filters = awaitFilters(filterChannel, 1) // just the second one

                // Drain accumulated: we received the first earlier, now check second
                assertEquals("""http and dst.name == "api-gateway"""", filters[0])
            },
            configFlow = config,
            filterChannel = filterChannel,
        )
    }

    @Test
    fun `config change takes effect on reconnect after server closes session`() {
        val filterChannel = Channel<String>(capacity = Channel.UNLIMITED)

        // Signal: test tells server handler it's OK to close the first session.
        val closeSignal = Channel<Unit>(capacity = 1)

        val config =
            MutableStateFlow(
                DynamicConfig(targetServices = mapOf("order-service" to "svc-1")),
            )

        withClient(
            serverBlock = {
                // Receive a close signal, then return to close the session.
                // Subsequent sessions hold open until the test scope ends.
                val isFirstSession = runCatching { closeSignal.tryReceive().isSuccess }.getOrDefault(false)
                if (isFirstSession) {
                    // Wait until the signal channel has an item (sent by the test).
                    closeSignal.receive()
                    // Returning closes the WebSocket, triggering a reconnect.
                } else {
                    awaitCancellation()
                }
            },
            testBlock = { _, flow ->
                // Wait for the first connection.
                awaitFilters(filterChannel, 1)

                // Update config so the reconnect picks up the new query.
                flow.value =
                    DynamicConfig(
                        targetServices = mapOf("api-gateway" to "svc-2"),
                    )

                // Tell the server to close the first session.
                closeSignal.send(Unit)

                // The client reconnects; await the new session's filter.
                val secondFilter = awaitFilters(filterChannel, 1)
                assertEquals("""http and dst.name == "api-gateway"""", secondFilter[0])
            },
            configFlow = config,
            filterChannel = filterChannel,
        )
    }

    // -----------------------------------------------------------------------
    // buildKflQuery — pure function, no server needed
    // -----------------------------------------------------------------------

    @Nested
    inner class BuildKflQueryTests {
        @Test
        fun `empty target services returns http only`() {
            assertEquals("http", KubesharkClient.buildKflQuery(emptyMap()))
        }

        @Test
        fun `single service returns http and dst name filter`() {
            val query = KubesharkClient.buildKflQuery(mapOf("order-service" to "svc-1"))
            assertEquals("""http and dst.name == "order-service"""", query)
        }

        @Test
        fun `two services returns http and parenthesised or filter`() {
            val query =
                KubesharkClient.buildKflQuery(
                    mapOf("order-service" to "svc-1", "api-gateway" to "svc-2"),
                )
            assertTrue(query.startsWith("http and ("), "should start with 'http and ('")
            assertTrue(query.endsWith(")"), "should end with ')'")
            assertTrue(query.contains("""dst.name == "order-service""""))
            assertTrue(query.contains("""dst.name == "api-gateway""""))
            assertTrue(query.contains(" or "))
        }

        @Test
        fun `three services returns http and parenthesised or filter`() {
            val query =
                KubesharkClient.buildKflQuery(
                    mapOf(
                        "svc-a" to "id-1",
                        "svc-b" to "id-2",
                        "svc-c" to "id-3",
                    ),
                )
            assertTrue(query.startsWith("http and ("))
            assertTrue(query.contains("""dst.name == "svc-a""""))
            assertTrue(query.contains("""dst.name == "svc-b""""))
            assertTrue(query.contains("""dst.name == "svc-c""""))
        }

        @Test
        fun `service ID values are not included in the KFL query`() {
            val query = KubesharkClient.buildKflQuery(mapOf("my-svc" to "platform-id-xyz"))
            assertTrue(query.contains("my-svc"))
            assertTrue(!query.contains("platform-id-xyz"))
        }

        @Test
        fun `names with embedded quotes are filtered out, leaving safe names`() {
            val query =
                KubesharkClient.buildKflQuery(
                    mapOf(
                        "order-service" to "id-1",
                        """svc" or true or dst.name == "x""" to "id-2",
                    ),
                )

            // The injection-shaped name is dropped; the legitimate name is retained.
            assertEquals("""http and dst.name == "order-service"""", query)
            assertTrue(!query.contains("or true"))
        }

        @Test
        fun `names with backslashes or control chars are filtered out`() {
            val query =
                KubesharkClient.buildKflQuery(
                    mapOf(
                        "order-service" to "id-1",
                        "svc\\backslash" to "id-2",
                        "svc\nnewline" to "id-3",
                    ),
                )

            assertEquals("""http and dst.name == "order-service"""", query)
        }

        @Test
        fun `names the server considers invalid but KFL can embed are still passed through`() {
            // The agent does not enforce RFC 1123 — that's the platform's job at POST /api/services.
            // The agent only refuses to embed strings that would change KFL semantics.
            // "Order-Service" is not RFC 1123 (uppercase) but is safe inside KFL quotes.
            val query = KubesharkClient.buildKflQuery(mapOf("Order-Service" to "id-1"))
            assertEquals("""http and dst.name == "Order-Service"""", query)
        }

        @Test
        fun `all unsafe names produce a no-match query, not http`() {
            val query =
                KubesharkClient.buildKflQuery(
                    mapOf(
                        """svc" or true""" to "id-1",
                        "another\"quote" to "id-2",
                    ),
                )

            // Critically, must NOT widen to "http" (which would capture all traffic).
            assertTrue(query != "http", "must not fall back to unfiltered 'http'")
            assertTrue(query.startsWith("http and dst.name == "), "should still be a dst.name filter")
        }
    }

    // -----------------------------------------------------------------------
    // drainBatch — transport and channel behaviour
    // -----------------------------------------------------------------------

    @Test
    fun `drainBatch returns entries produced by the streamer`() =
        withClient(
            serverBlock = {
                send(Frame.Text(wsEntry("e1", 1000L)))
                send(Frame.Text(wsEntry("e2", 2000L)))
                send(Frame.Text(wsEntry("e3", 3000L)))
                awaitCancellation()
            },
            testBlock = { client, _ ->
                val entries = drainUntil(client, 3)

                assertEquals(3, entries.size)
                assertEquals("e1", entries[0].id)
                assertEquals(1000L, entries[0].timestamp)
                assertEquals("GET", entries[0].request?.method)
                assertEquals("/api/test", entries[0].request?.url)
                assertEquals(200, entries[0].response?.status)
            },
        )

    @Test
    fun `drainBatch respects the limit parameter`() =
        withClient(
            serverBlock = {
                repeat(200) { i ->
                    send(Frame.Text(wsEntry("e$i", (i + 1) * 1000L)))
                }
                awaitCancellation()
            },
            testBlock = { client, _ ->
                // Drain all 200 entries via drainBatch(limit=3). Every batch
                // must respect the limit.
                val all = mutableListOf<KubesharkEntry>()
                withTimeout(30.seconds) {
                    while (all.size < 200) {
                        val batch = client.drainBatch(limit = 3, maxWait = 5.seconds)
                        if (batch.isNotEmpty()) {
                            assertTrue(batch.size <= 3, "batch size ${batch.size} exceeded limit 3")
                        }
                        all.addAll(batch)
                    }
                }
                assertEquals(200, all.size, "all entries should be drained")
            },
        )

    @Test
    fun `drainBatch returns empty on timeout but recovers when traffic arrives later`() =
        withClient(
            serverBlock = {
                // The first drainBatch call below uses maxWait=100ms. We delay
                // longer than that so the first call definitely times out, then
                // send an entry for the second call to pick up.
                kotlinx.coroutines.delay(300.milliseconds)
                send(Frame.Text(wsEntry("delayed-1", 5000L)))
                awaitCancellation()
            },
            testBlock = { client, _ ->
                // First drain — server hasn't sent anything yet, should time out.
                val empty = client.drainBatch(limit = 100, maxWait = 100.milliseconds)
                assertTrue(empty.isEmpty(), "drainBatch should return empty when no entries arrive within maxWait")

                // Second drain — server will eventually send an entry; suspension
                // means we return as soon as it arrives.
                val afterTimeout = drainUntil(client, 1)
                assertEquals(1, afterTimeout.size)
                assertEquals("delayed-1", afterTimeout[0].id)
            },
        )

    @Test
    fun `drainBatch yields multiple batches from a single persistent session`() =
        withClient(
            serverBlock = {
                send(Frame.Text(wsEntry("e1", 1000L)))
                send(Frame.Text(wsEntry("e2", 2000L)))
                // Small delay so the first two entries may be drained before e3/e4
                // arrive, exercising the "multiple batches from one session" path.
                kotlinx.coroutines.delay(50.milliseconds)
                send(Frame.Text(wsEntry("e3", 3000L)))
                send(Frame.Text(wsEntry("e4", 4000L)))
                awaitCancellation()
            },
            testBlock = { client, _ ->
                // Drain all 4 entries across however many batches the session produces.
                val allEntries = drainUntil(client, 4)

                assertEquals(4, allEntries.size)
                val ids = allEntries.map { it.id }.toSet()
                assertTrue(ids.containsAll(setOf("e1", "e2", "e3", "e4")))
            },
        )

    @Test
    fun `drainBatch skips unparseable JSON messages`() =
        withClient(
            serverBlock = {
                send(Frame.Text("not valid json"))
                send(Frame.Text(wsEntry("e1", 1000L)))
                send(Frame.Text("{incomplete"))
                send(Frame.Text(wsEntry("e2", 2000L)))
                awaitCancellation()
            },
            testBlock = { client, _ ->
                val entries = drainUntil(client, 2)

                assertEquals(2, entries.size)
                assertEquals("e1", entries[0].id)
                assertEquals("e2", entries[1].id)
            },
        )

    @Test
    fun `drainBatch drops entries older than the dedup lookback window`() =
        withClient(
            serverBlock = {
                send(Frame.Text(wsEntry("e1", 1_000_000L)))
                send(Frame.Text(wsEntry("replay", 900_000L))) // 100s old — dropped
                send(Frame.Text(wsEntry("e2", 1_001_000L)))
                awaitCancellation()
            },
            testBlock = { client, _ ->
                val entries = drainUntil(client, 2)

                assertEquals(2, entries.size)
                assertEquals("e1", entries[0].id)
                assertEquals("e2", entries[1].id)
            },
        )

    @Test
    fun `drainBatch preserves entries that are out of order within the lookback window`() =
        withClient(
            serverBlock = {
                send(Frame.Text(wsEntry("e1", 10_000L)))
                send(Frame.Text(wsEntry("e2", 12_000L)))
                send(Frame.Text(wsEntry("e3", 9_000L))) // 3s behind e2, within 5s window
                send(Frame.Text(wsEntry("e4", 13_000L)))
                awaitCancellation()
            },
            testBlock = { client, _ ->
                val entries = drainUntil(client, 4)

                assertEquals(4, entries.size)
                assertEquals("e1", entries[0].id)
                assertEquals("e2", entries[1].id)
                assertEquals("e3", entries[2].id)
                assertEquals("e4", entries[3].id)
            },
        )

    // -----------------------------------------------------------------------
    // isConnected — tracks WebSocket session state for liveness probe
    // -----------------------------------------------------------------------

    @Test
    fun `isConnected becomes true once WebSocket session is open`() =
        withClient(
            serverBlock = {
                send(Frame.Text(wsEntry("e1", 1000L)))
                awaitCancellation()
            },
            testBlock = { client, _ ->
                // Drain proves the WebSocket is fully open and a frame was processed.
                drainUntil(client, 1)
                assertTrue(client.isConnected(), "isConnected should be true while session is open")
            },
        )

    @Test
    fun `isConnected becomes false during reconnect delay after server closes session`() {
        val sessionCount = AtomicInteger(0)
        withClient(
            serverBlock = {
                val session = sessionCount.incrementAndGet()
                if (session == 1) {
                    // First session: send an entry, then return to close the WebSocket.
                    send(Frame.Text(wsEntry("e1", 1000L)))
                } else {
                    // Subsequent reconnects: hold open until the test scope ends.
                    awaitCancellation()
                }
            },
            // Long reconnect delay opens a stable window where isConnected must be false.
            reconnectDelay = 5.seconds,
            testBlock = { client, _ ->
                drainUntil(client, 1)
                // Server closes after sending; poll until the finally-block flips connected to false.
                withTimeout(2.seconds) {
                    while (client.isConnected()) kotlinx.coroutines.delay(10)
                }
                assertFalse(client.isConnected(), "isConnected should be false during reconnect delay")
            },
        )
    }
}
