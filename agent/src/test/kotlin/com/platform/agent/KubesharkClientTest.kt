package com.platform.agent

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun `collects entries from WebSocket stream`() =
        runBlocking {
            val server =
                embeddedServer(Netty, port = 0) {
                    install(io.ktor.server.websocket.WebSockets)
                    routing {
                        webSocket("/api/wsFull") {
                            // Read the KFL filter
                            val filter = (incoming.receive() as Frame.Text).readText()
                            assertEquals("http", filter)

                            // Send entries
                            send(Frame.Text(wsEntry("e1", 1000L)))
                            send(Frame.Text(wsEntry("e2", 2000L)))
                            send(Frame.Text(wsEntry("e3", 3000L)))

                            // Keep connection open briefly so client can read
                            delay(500)
                        }
                    }
                }
            server.start(wait = false)
            val port =
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port

            try {
                val httpClient =
                    HttpClient(io.ktor.client.engine.cio.CIO) {
                        install(WebSockets)
                    }
                val client = KubesharkClient(httpClient, "http://127.0.0.1:$port")

                val entries = client.listHttpCalls(startMs = null, limit = 100)

                assertEquals(3, entries.size)
                assertEquals("e1", entries[0].id)
                assertEquals(1000L, entries[0].timestamp)
                assertEquals("GET", entries[0].request?.method)
                assertEquals("/api/test", entries[0].request?.url)
                assertEquals(200, entries[0].response?.status)
            } finally {
                server.stop(100, 100)
            }
        }

    @Test
    fun `respects limit parameter`() =
        runBlocking {
            val server =
                embeddedServer(Netty, port = 0) {
                    install(io.ktor.server.websocket.WebSockets)
                    routing {
                        webSocket("/api/wsFull") {
                            incoming.receive() // KFL filter
                            send(Frame.Text(wsEntry("e1", 1000L)))
                            send(Frame.Text(wsEntry("e2", 2000L)))
                            send(Frame.Text(wsEntry("e3", 3000L)))
                            delay(500)
                        }
                    }
                }
            server.start(wait = false)
            val port =
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port

            try {
                val httpClient =
                    HttpClient(io.ktor.client.engine.cio.CIO) {
                        install(WebSockets)
                    }
                val client = KubesharkClient(httpClient, "http://127.0.0.1:$port")

                val entries = client.listHttpCalls(startMs = null, limit = 2)

                assertEquals(2, entries.size)
            } finally {
                server.stop(100, 100)
            }
        }

    @Test
    fun `filters entries by startMs`() =
        runBlocking {
            val server =
                embeddedServer(Netty, port = 0) {
                    install(io.ktor.server.websocket.WebSockets)
                    routing {
                        webSocket("/api/wsFull") {
                            incoming.receive() // KFL filter
                            send(Frame.Text(wsEntry("e1", 1000L)))
                            send(Frame.Text(wsEntry("e2", 2000L)))
                            send(Frame.Text(wsEntry("e3", 3000L)))
                            delay(500)
                        }
                    }
                }
            server.start(wait = false)
            val port =
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port

            try {
                val httpClient =
                    HttpClient(io.ktor.client.engine.cio.CIO) {
                        install(WebSockets)
                    }
                val client = KubesharkClient(httpClient, "http://127.0.0.1:$port")

                val entries = client.listHttpCalls(startMs = 2000L, limit = 100)

                assertEquals(2, entries.size)
                assertEquals("e2", entries[0].id)
                assertEquals("e3", entries[1].id)
            } finally {
                server.stop(100, 100)
            }
        }

    @Test
    fun `returns empty list on connection failure`() =
        runBlocking {
            val httpClient =
                HttpClient(io.ktor.client.engine.cio.CIO) {
                    install(WebSockets)
                }
            // Point to a port nothing is listening on
            val client = KubesharkClient(httpClient, "http://127.0.0.1:19999")

            val entries = client.listHttpCalls(startMs = null, limit = 100)

            assertTrue(entries.isEmpty())
        }

    @Test
    fun `handles malformed JSON messages gracefully`() =
        runBlocking {
            val server =
                embeddedServer(Netty, port = 0) {
                    install(io.ktor.server.websocket.WebSockets)
                    routing {
                        webSocket("/api/wsFull") {
                            incoming.receive() // KFL filter
                            send(Frame.Text("not valid json"))
                            send(Frame.Text(wsEntry("e1", 1000L)))
                            send(Frame.Text("{incomplete"))
                            send(Frame.Text(wsEntry("e2", 2000L)))
                            delay(500)
                        }
                    }
                }
            server.start(wait = false)
            val port =
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port

            try {
                val httpClient =
                    HttpClient(io.ktor.client.engine.cio.CIO) {
                        install(WebSockets)
                    }
                val client = KubesharkClient(httpClient, "http://127.0.0.1:$port")

                val entries = client.listHttpCalls(startMs = null, limit = 100)

                // Should have the 2 valid entries, skipping the malformed ones
                assertEquals(2, entries.size)
                assertEquals("e1", entries[0].id)
                assertEquals("e2", entries[1].id)
            } finally {
                server.stop(100, 100)
            }
        }
}
