package com.platform.agent

import com.platform.agent.models.KubesharkEntry
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
 * StateFlow-driven KFL reconnect, and [KubesharkClient.buildKflQuery].
 *
 * Each transport test spins up an embedded Ktor Netty server on a random port
 * that implements a fake `/api/wsFull` handler. The real [KubesharkClient]
 * connects to it over a real WebSocket, so we're exercising the full transport
 * stack (Ktor CIO client → TCP → Ktor Netty server) without mocking.
 *
 * All tests use polling with deadlines instead of fixed delays to avoid
 * flakiness on slow CI runners.
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

    private fun withClient(
        serverBlock: suspend DefaultWebSocketServerSession.() -> Unit,
        testBlock: suspend CoroutineScope.(KubesharkClient, MutableStateFlow<DynamicConfig>) -> Unit,
        configFlow: MutableStateFlow<DynamicConfig> = MutableStateFlow(DynamicConfig.default()),
        reconnectDelay: Duration = 50.milliseconds,
        receivedFilters: MutableList<String> = mutableListOf(),
    ) = runBlocking {
        val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
            embeddedServer(Netty, port = 0) {
                install(ServerWebSockets)
                routing {
                    webSocket("/api/wsFull") {
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

    /** Poll until [receivedFilters] has at least [count] entries, or time out. */
    private suspend fun awaitFilters(
        receivedFilters: MutableList<String>,
        count: Int,
        timeoutMs: Long = 10_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (synchronized(receivedFilters) { receivedFilters.size } < count &&
            System.currentTimeMillis() < deadline
        ) {
            delay(10.milliseconds)
        }
    }

    /** Poll drainBatch until [count] entries accumulated, or time out. */
    private suspend fun drainUntil(
        client: KubesharkClient,
        count: Int,
        timeoutMs: Long = 10_000,
    ): List<KubesharkEntry> {
        val entries = mutableListOf<KubesharkEntry>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (entries.size < count && System.currentTimeMillis() < deadline) {
            entries.addAll(client.drainBatch(limit = 100, maxWait = 200.milliseconds))
        }
        return entries
    }

    // -----------------------------------------------------------------------
    // KFL filter transmission — assert what the server actually receives
    // -----------------------------------------------------------------------

    @Test
    fun `sends http-only KFL filter when no target services configured`() {
        val receivedFilters = mutableListOf<String>()
        withClient(
            serverBlock = { delay(2_000.milliseconds) },
            testBlock = { _, _ ->
                awaitFilters(receivedFilters, 1)
            },
            receivedFilters = receivedFilters,
        )
        assertEquals(listOf("http"), receivedFilters)
    }

    @Test
    fun `sends KFL query with target services on connect`() {
        val receivedFilters = mutableListOf<String>()
        val config =
            MutableStateFlow(
                DynamicConfig(targetServices = mapOf("order-service" to "svc-1")),
            )
        withClient(
            serverBlock = { delay(2_000.milliseconds) },
            testBlock = { _, _ ->
                awaitFilters(receivedFilters, 1)
            },
            configFlow = config,
            receivedFilters = receivedFilters,
        )
        assertEquals(
            listOf("""http and dst.name == "order-service""""),
            receivedFilters,
        )
    }

    @Test
    fun `same targetServices update does not trigger reconnect`() {
        val receivedFilters = mutableListOf<String>()
        val config =
            MutableStateFlow(
                DynamicConfig(targetServices = mapOf("order-service" to "svc-1")),
            )

        withClient(
            serverBlock = { delay(500.milliseconds) },
            testBlock = { _, flow ->
                awaitFilters(receivedFilters, 1)

                // Update with same targetServices but different samplingRate
                flow.value =
                    DynamicConfig(
                        targetServices = mapOf("order-service" to "svc-1"),
                        samplingRate = 0.5,
                    )

                delay(200.milliseconds)
            },
            configFlow = config,
            receivedFilters = receivedFilters,
        )

        assertEquals(1, receivedFilters.size, "same targetServices must not trigger reconnect")
    }

    @Test
    fun `targetServices change forces immediate reconnect`() {
        val receivedFilters = mutableListOf<String>()
        val config =
            MutableStateFlow(
                DynamicConfig(targetServices = mapOf("order-service" to "svc-1")),
            )

        withClient(
            serverBlock = { delay(2_000.milliseconds) },
            testBlock = { _, flow ->
                awaitFilters(receivedFilters, 1)

                // Change targetServices via the StateFlow
                flow.value =
                    DynamicConfig(
                        targetServices = mapOf("api-gateway" to "svc-2"),
                    )

                awaitFilters(receivedFilters, 2)
            },
            configFlow = config,
            receivedFilters = receivedFilters,
        )

        assertEquals(2, receivedFilters.size)
        assertEquals("""http and dst.name == "order-service"""", receivedFilters[0])
        assertEquals("""http and dst.name == "api-gateway"""", receivedFilters[1])
    }

    @Test
    fun `config change takes effect on reconnect after server closes session`() {
        val allowFirstSessionToClose = AtomicBoolean(false)
        val receivedFilters = mutableListOf<String>()
        val config =
            MutableStateFlow(
                DynamicConfig(targetServices = mapOf("order-service" to "svc-1")),
            )

        withClient(
            serverBlock = {
                val sessionIndex = synchronized(receivedFilters) { receivedFilters.size }
                when (sessionIndex) {
                    1 -> {
                        val deadline = System.currentTimeMillis() + 2_000
                        while (!allowFirstSessionToClose.get() && System.currentTimeMillis() < deadline) {
                            delay(10.milliseconds)
                        }
                    }
                    else -> delay(500.milliseconds)
                }
            },
            testBlock = { _, flow ->
                awaitFilters(receivedFilters, 1)

                flow.value =
                    DynamicConfig(
                        targetServices = mapOf("api-gateway" to "svc-2"),
                    )
                allowFirstSessionToClose.set(true)

                awaitFilters(receivedFilters, 2)
            },
            configFlow = config,
            receivedFilters = receivedFilters,
        )

        assertEquals(2, receivedFilters.size)
        assertEquals("""http and dst.name == "order-service"""", receivedFilters[0])
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
                delay(2_000.milliseconds)
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
                repeat(10) { i ->
                    send(Frame.Text(wsEntry("e$i", (i + 1) * 1000L)))
                }
                delay(2_000.milliseconds)
            },
            testBlock = { client, _ ->
                // Drain a few entries first to ensure the WebSocket session is
                // established and remaining entries are buffered in the channel.
                drainUntil(client, 3)

                // Now the channel has entries ready — limit is the only constraint.
                val batch = client.drainBatch(limit = 3, maxWait = 1_000.milliseconds)
                assertTrue(batch.isNotEmpty(), "should have entries buffered")
                assertTrue(batch.size <= 3, "batch size ${batch.size} exceeded limit 3")
            },
        )

    @Test
    fun `drainBatch returns empty on timeout but recovers when traffic arrives later`() =
        withClient(
            serverBlock = {
                // Wait long enough that the first drainBatch (100ms maxWait) definitely times out
                delay(1_000.milliseconds)
                send(Frame.Text(wsEntry("delayed-1", 5000L)))
                delay(2_000.milliseconds)
            },
            testBlock = { client, _ ->
                // First drain — server hasn't sent anything yet, times out
                val empty = client.drainBatch(limit = 100, maxWait = 100.milliseconds)
                assertTrue(empty.isEmpty(), "drainBatch should return empty when no entries arrive within maxWait")

                // Second drain — server will eventually send an entry
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
                delay(500.milliseconds)
                send(Frame.Text(wsEntry("e3", 3000L)))
                send(Frame.Text(wsEntry("e4", 4000L)))
                delay(2_000.milliseconds)
            },
            testBlock = { client, _ ->
                // Drain all 4 entries across multiple batches
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
                delay(2_000.milliseconds)
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
                delay(2_000.milliseconds)
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
                delay(2_000.milliseconds)
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
}
