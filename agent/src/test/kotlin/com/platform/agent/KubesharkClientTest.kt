package com.platform.agent

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests for [KubesharkClient]'s persistent WebSocket session and drainBatch API.
 *
 * Each test spins up an embedded Ktor Netty server on a random port that
 * implements a fake `/api/wsFull` handler. The real [KubesharkClient] connects
 * to it over a real WebSocket, so we're exercising the full transport stack
 * (Ktor CIO client → TCP → Ktor Netty server) without mocking.
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
     */
    private fun withClient(
        serverBlock: suspend io.ktor.server.websocket.DefaultWebSocketServerSession.() -> Unit,
        testBlock: suspend CoroutineScope.(KubesharkClient) -> Unit,
    ) = runBlocking {
        val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
            embeddedServer(Netty, port = 0) {
                install(io.ktor.server.websocket.WebSockets)
                routing {
                    webSocket("/api/wsFull") {
                        // Read the KFL filter and assert it's empty
                        val filter = (incoming.receive() as Frame.Text).readText()
                        assertEquals("", filter)
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
            HttpClient(io.ktor.client.engine.cio.CIO) {
                install(WebSockets)
            }

        try {
            val client = KubesharkClient(httpClient, "http://127.0.0.1:$port", this)
            testBlock(client)
        } finally {
            httpClient.close()
            server.stop(100, 100)
        }
    }

    @Test
    fun `drainBatch returns entries produced by the streamer`() =
        withClient(
            serverBlock = {
                send(Frame.Text(wsEntry("e1", 1000L)))
                send(Frame.Text(wsEntry("e2", 2000L)))
                send(Frame.Text(wsEntry("e3", 3000L)))
                // Hold the connection open long enough for the client to drain
                delay(500)
            },
            testBlock = { client ->
                // Give the streamer a moment to move all 3 frames into the channel
                delay(200)
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
                delay(500)
            },
            testBlock = { client ->
                // Wait briefly so the streamer has time to move everything into the channel
                delay(200)
                val entries = client.drainBatch(limit = 3, maxWait = 2000.milliseconds)

                assertEquals(3, entries.size)
            },
        )

    @Test
    fun `drainBatch returns empty list when no entries arrive within maxWait`() =
        withClient(
            serverBlock = {
                // Server accepts the connection, reads the filter, but sends nothing
                delay(2000)
            },
            testBlock = { client ->
                val entries = client.drainBatch(limit = 100, maxWait = 200.milliseconds)
                assertTrue(entries.isEmpty())
            },
        )

    @Test
    fun `drainBatch yields multiple batches from a single persistent session`() =
        withClient(
            serverBlock = {
                send(Frame.Text(wsEntry("e1", 1000L)))
                send(Frame.Text(wsEntry("e2", 2000L)))
                delay(500)
                // Second burst after the first batch has been drained
                send(Frame.Text(wsEntry("e3", 3000L)))
                send(Frame.Text(wsEntry("e4", 4000L)))
                delay(500)
            },
            testBlock = { client ->
                delay(200)
                val firstBatch = client.drainBatch(limit = 100, maxWait = 1000.milliseconds)
                assertEquals(2, firstBatch.size)
                assertEquals("e1", firstBatch[0].id)

                delay(300)
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
                delay(500)
            },
            testBlock = { client ->
                delay(200)
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
                delay(500)
            },
            testBlock = { client ->
                delay(200)
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
                delay(500)
            },
            testBlock = { client ->
                delay(200)
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
