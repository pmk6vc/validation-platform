package com.platform.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/**
 * Tests for [KubesharkClient]'s persistent WebSocket session, drainBatch API,
 * KFL query management ([updateKflQuery]), and [KubesharkClient.buildKflQuery].
 *
 * Each transport test spins up an embedded Ktor Netty server on a random port
 * that implements a fake `/api/wsFull` handler. The real [KubesharkClient]
 * connects to it over a real WebSocket, so we're exercising the full transport
 * stack (Ktor CIO client → TCP → Ktor Netty server) without mocking.
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
     * Run a test scoped around an embedded Ktor server + a [KubesharkClient].
     * Handles server startup/teardown and HTTP client creation. The test body
     * runs inside the [CoroutineScope] that owns the client's streamer job,
     * so cancelling the scope (via `runBlocking` exit) tears everything down.
     *
     * @param initialKflQuery KFL query to pass as [KubesharkClient.initialKflQuery].
     * @param captureReceivedFilter When true, the server records the KFL filter
     *   frame it receives into [receivedFilters] so tests can assert on it.
     * @param receivedFilters Mutable list populated with each KFL filter the
     *   server receives (one per WebSocket session the client opens).
     */
    private fun withClient(
        serverBlock: suspend DefaultWebSocketServerSession.() -> Unit,
        testBlock: suspend CoroutineScope.(KubesharkClient) -> Unit,
        initialKflQuery: String = "",
        reconnectDelay: Duration = KubesharkClient.DEFAULT_RECONNECT_DELAY,
        receivedFilters: MutableList<String> = mutableListOf(),
    ) = runBlocking {
        val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
            embeddedServer(Netty, port = 0) {
                install(ServerWebSockets)
                routing {
                    webSocket("/api/wsFull") {
                        // Read the KFL filter frame and record it for the test to assert on
                        val filter = (incoming.receive() as Frame.Text).readText()
                        synchronized(receivedFilters) { receivedFilters.add(filter) }
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

        val httpClient =
            HttpClient(CIO) {
                install(WebSockets)
            }

        try {
            val client =
                KubesharkClient(
                    httpClient = httpClient,
                    baseUrl = "http://127.0.0.1:$port",
                    scope = this,
                    initialKflQuery = initialKflQuery,
                    reconnectDelay = reconnectDelay,
                )
            testBlock(client)
        } finally {
            httpClient.close()
            server.stop(100, 100)
        }
    }

    // -----------------------------------------------------------------------
    // KFL filter transmission — assert what the server actually receives
    // -----------------------------------------------------------------------

    @Test
    fun `sends empty KFL filter when constructed with default`() {
        val receivedFilters = mutableListOf<String>()
        withClient(
            serverBlock = { delay(300.milliseconds) },
            testBlock = { delay(200.milliseconds) },
            receivedFilters = receivedFilters,
        )
        assertEquals(listOf(""), receivedFilters)
    }

    @Test
    fun `sends provided KFL query on connect`() {
        val kflQuery = """http and dst.name == "order-service""""
        val receivedFilters = mutableListOf<String>()
        withClient(
            serverBlock = { delay(300.milliseconds) },
            testBlock = { delay(200.milliseconds) },
            initialKflQuery = kflQuery,
            receivedFilters = receivedFilters,
        )
        assertEquals(listOf(kflQuery), receivedFilters)
    }

    @Test
    fun `updateKflQuery with the same query is a no-op and does not reconnect`() {
        val receivedFilters = mutableListOf<String>()

        withClient(
            serverBlock = {
                // Hold the session open; if the client reconnects unexpectedly
                // this block will be entered a second time and receivedFilters
                // will have two entries, failing the assertion below.
                delay(500.milliseconds)
            },
            testBlock = { client ->
                // Wait for the first (and only) session to open
                val deadline = System.currentTimeMillis() + 2_000
                while (synchronized(receivedFilters) { receivedFilters.size } < 1 &&
                    System.currentTimeMillis() < deadline
                ) {
                    delay(10.milliseconds)
                }

                // Calling with the same query must NOT trigger a reconnect
                client.updateKflQuery("http")
                client.updateKflQuery("http")

                // Give enough time for a reconnect to appear (it shouldn't)
                delay(200.milliseconds)
            },
            initialKflQuery = "http",
            reconnectDelay = 50.milliseconds,
            receivedFilters = receivedFilters,
        )

        assertEquals(1, receivedFilters.size, "same-query update must not trigger a reconnect")
    }

    @Test
    fun `updateKflQuery with a different query forces immediate reconnect without waiting for server close`() {
        // The server holds its session open indefinitely — the client must
        // reconnect on its own when the query changes, without waiting for the
        // server to close the WebSocket.
        val receivedFilters = mutableListOf<String>()

        withClient(
            serverBlock = {
                // Hold open; the client-side cancel forces a reconnect regardless
                delay(2_000.milliseconds)
            },
            testBlock = { client ->
                // Wait for the first session to open
                val firstDeadline = System.currentTimeMillis() + 2_000
                while (synchronized(receivedFilters) { receivedFilters.size } < 1 &&
                    System.currentTimeMillis() < firstDeadline
                ) {
                    delay(10.milliseconds)
                }

                // Change the query — this must cancel the active session immediately
                client.updateKflQuery("""http and dst.name == "api-gateway"""")

                // Wait for the second session to open with the updated filter
                val secondDeadline = System.currentTimeMillis() + 2_000
                while (synchronized(receivedFilters) { receivedFilters.size } < 2 &&
                    System.currentTimeMillis() < secondDeadline
                ) {
                    delay(10.milliseconds)
                }
            },
            initialKflQuery = "http",
            reconnectDelay = 50.milliseconds,
            receivedFilters = receivedFilters,
        )

        assertEquals(2, receivedFilters.size)
        assertEquals("http", receivedFilters[0])
        assertEquals("""http and dst.name == "api-gateway"""", receivedFilters[1])
    }

    @Test
    fun `updateKflQuery takes effect on reconnect after server closes session`() {
        // Co-ordinate via a flag so there are no timing races:
        //   1. First session opens → server waits for flag before closing
        //   2. testBlock updates the query, then sets the flag
        //   3. Server sees the flag, returns (closes session) → client reconnects
        //   4. Second session opens and sends the updated KFL query
        val allowFirstSessionToClose = AtomicBoolean(false)
        val receivedFilters = mutableListOf<String>()

        withClient(
            serverBlock = {
                val sessionIndex = synchronized(receivedFilters) { receivedFilters.size }
                when (sessionIndex) {
                    1 -> {
                        // First session: poll the flag; once set, return to close
                        val deadline = System.currentTimeMillis() + 2_000
                        while (!allowFirstSessionToClose.get() && System.currentTimeMillis() < deadline) {
                            delay(10.milliseconds)
                        }
                        // Returning closes the session and triggers a reconnect
                    }
                    else -> {
                        // Second session: hold open so the test can assert
                        delay(500.milliseconds)
                    }
                }
            },
            testBlock = { client ->
                // Wait for first session to record its filter
                val firstFilterDeadline = System.currentTimeMillis() + 2_000
                while (synchronized(receivedFilters) { receivedFilters.size } < 1 &&
                    System.currentTimeMillis() < firstFilterDeadline
                ) {
                    delay(10.milliseconds)
                }

                // Update the query before closing the first session, so the
                // reconnect picks up the new value
                client.updateKflQuery("""http and dst.name == "api-gateway"""")
                allowFirstSessionToClose.set(true)

                // Wait for second session to open and record its filter
                val secondFilterDeadline = System.currentTimeMillis() + 2_000
                while (synchronized(receivedFilters) { receivedFilters.size } < 2 &&
                    System.currentTimeMillis() < secondFilterDeadline
                ) {
                    delay(10.milliseconds)
                }
            },
            initialKflQuery = "http",
            reconnectDelay = 50.milliseconds,
            receivedFilters = receivedFilters,
        )

        // First session used the initial query; second session used the updated one
        assertEquals(2, receivedFilters.size)
        assertEquals("http", receivedFilters[0])
        assertEquals("""http and dst.name == "api-gateway"""", receivedFilters[1])
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
            // Both service names must appear; order is map iteration order
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
            // Only the K8s service name (key) should appear in the query,
            // not the platform service ID (value).
            val query = KubesharkClient.buildKflQuery(mapOf("my-svc" to "platform-id-xyz"))
            assertTrue(query.contains("my-svc"))
            assertTrue(!query.contains("platform-id-xyz"))
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
                // Hold the connection open long enough for the client to drain
                delay(500.milliseconds)
            },
            testBlock = { client ->
                // Give the streamer a moment to move all 3 frames into the channel
                delay(200.milliseconds)
                val entries = client.drainBatch(limit = 100, maxWait = 2000.milliseconds)

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
                repeat(10) { i ->
                    send(Frame.Text(wsEntry("e$i", (i + 1) * 1000L)))
                }
                delay(500.milliseconds)
            },
            testBlock = { client ->
                // Wait briefly so the streamer has time to move everything into the channel
                delay(200.milliseconds)
                val entries = client.drainBatch(limit = 3, maxWait = 2000.milliseconds)

                assertEquals(3, entries.size)
            },
        )

    @Test
    fun `drainBatch returns empty on timeout but recovers when traffic arrives later`() =
        withClient(
            serverBlock = {
                // First drainBatch has maxWait=200ms; the server waits 400ms
                // before sending so the first call times out with an empty
                // list. Then it sends an entry, which the second drainBatch
                // call should pick up — proving the empty-timeout path does
                // not leave the channel in a bad state.
                delay(400.milliseconds)
                send(Frame.Text(wsEntry("delayed-1", 5000L)))
                delay(500.milliseconds)
            },
            testBlock = { client ->
                // First drain — server hasn't sent anything yet, times out
                val empty = client.drainBatch(limit = 100, maxWait = 200.milliseconds)
                assertTrue(empty.isEmpty(), "drainBatch should return empty when no entries arrive within maxWait")

                // Second drain — server has since sent an entry, we should get it
                val afterTimeout = client.drainBatch(limit = 100, maxWait = 1000.milliseconds)
                assertEquals(
                    1,
                    afterTimeout.size,
                    "drainBatch should still pick up entries that arrive after a prior timeout",
                )
                assertEquals("delayed-1", afterTimeout[0].id)
            },
        )

    @Test
    fun `drainBatch yields multiple batches from a single persistent session`() =
        withClient(
            serverBlock = {
                send(Frame.Text(wsEntry("e1", 1000L)))
                send(Frame.Text(wsEntry("e2", 2000L)))
                delay(500.milliseconds)
                // Second burst after the first batch has been drained
                send(Frame.Text(wsEntry("e3", 3000L)))
                send(Frame.Text(wsEntry("e4", 4000L)))
                delay(500.milliseconds)
            },
            testBlock = { client ->
                delay(200.milliseconds)
                val firstBatch = client.drainBatch(limit = 100, maxWait = 1000.milliseconds)
                assertEquals(2, firstBatch.size)
                assertEquals("e1", firstBatch[0].id)

                delay(300.milliseconds)
                val secondBatch = client.drainBatch(limit = 100, maxWait = 1000.milliseconds)
                assertEquals(2, secondBatch.size)
                assertEquals("e3", secondBatch[0].id)
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
                delay(500.milliseconds)
            },
            testBlock = { client ->
                delay(200.milliseconds)
                val entries = client.drainBatch(limit = 100, maxWait = 1000.milliseconds)

                // The two valid entries survive; the malformed frames are dropped silently
                assertEquals(2, entries.size)
                assertEquals("e1", entries[0].id)
                assertEquals("e2", entries[1].id)
            },
        )

    @Test
    fun `drainBatch drops entries older than the dedup lookback window`() =
        withClient(
            serverBlock = {
                // DEDUP_LOOKBACK is 5 seconds. After we've seen ts=1_000_000, any
                // entry with ts < 995_000 must be dropped as reconnect-replay noise.
                send(Frame.Text(wsEntry("e1", 1_000_000L)))
                send(Frame.Text(wsEntry("replay", 900_000L))) // 100s old — dropped
                send(Frame.Text(wsEntry("e2", 1_001_000L)))
                delay(500.milliseconds)
            },
            testBlock = { client ->
                delay(200.milliseconds)
                val entries = client.drainBatch(limit = 100, maxWait = 1000.milliseconds)

                assertEquals(2, entries.size)
                assertEquals("e1", entries[0].id)
                assertEquals("e2", entries[1].id)
            },
        )

    @Test
    fun `drainBatch preserves entries that are out of order within the lookback window`() =
        withClient(
            serverBlock = {
                // DEDUP_LOOKBACK is 5 seconds. In-session out-of-order entries
                // within that window must still be forwarded even though they're
                // older than lastSeen.
                send(Frame.Text(wsEntry("e1", 10_000L)))
                send(Frame.Text(wsEntry("e2", 12_000L)))
                send(Frame.Text(wsEntry("e3", 9_000L))) // 3s behind e2, within 5s window
                send(Frame.Text(wsEntry("e4", 13_000L)))
                delay(500.milliseconds)
            },
            testBlock = { client ->
                delay(200.milliseconds)
                val entries = client.drainBatch(limit = 100, maxWait = 1000.milliseconds)

                // All four entries survive — e3 is within the 5s lookback window
                assertEquals(4, entries.size)
                assertEquals("e1", entries[0].id)
                assertEquals("e2", entries[1].id)
                assertEquals("e3", entries[2].id)
                assertEquals("e4", entries[3].id)
            },
        )
}
