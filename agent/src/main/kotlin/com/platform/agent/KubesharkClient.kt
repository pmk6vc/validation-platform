package com.platform.agent

import com.platform.agent.models.KubesharkEntry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Client that connects to Kubeshark's WebSocket /wsFull endpoint to receive
 * real-time L7 API call entries.
 *
 * Kubeshark v53+ exposes traffic data exclusively over WebSocket — there is
 * no REST API for querying entries. The client connects through the front's
 * nginx proxy at /api/wsFull (same path the browser uses), which proxies to
 * the hub's /wsFull with proper WebSocket upgrade headers. An empty KFL filter
 * is sent so the server streams all entries; client-side filtering is handled
 * by [TrafficTransformer].
 *
 * Each call to [listHttpCalls] opens a fresh WebSocket session, collects up
 * to [limit] entries newer than [startMs], and closes the session. This
 * connect-per-poll approach is simple and stateless. If performance becomes
 * an issue with high-traffic clusters, we can switch to a persistent session
 * that buffers entries into a channel.
 */
class KubesharkClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) {
    private val logger = LoggerFactory.getLogger(KubesharkClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    // Parse connection details once at construction
    private val parsedUri = URI.create(baseUrl)
    private val wsHost = parsedUri.host
    private val wsPort = if (parsedUri.port > 0) parsedUri.port else 80

    /**
     * Fetch HTTP API calls from Kubeshark via WebSocket.
     *
     * Opens a WebSocket to /wsFull, sends an empty KFL filter (no server-side
     * filtering), and collects entries until [limit] is reached or no new entry
     * arrives within the collect timeout window. Filtering is done client-side
     * by [TrafficTransformer].
     *
     * @param startMs Unix timestamp in milliseconds — only return entries after this time
     * @param limit Maximum number of entries to return
     * @return List of Kubeshark entries (unfiltered — caller handles service filtering)
     */
    suspend fun listHttpCalls(
        startMs: Long? = null,
        limit: Int = 100,
    ): List<KubesharkEntry> =
        try {
            collectEntries(startMs, limit)
        } catch (e: Exception) {
            logger.error("Kubeshark WebSocket error", e)
            emptyList()
        }

    private suspend fun collectEntries(
        startMs: Long?,
        limit: Int,
    ): List<KubesharkEntry> {
        val entries = mutableListOf<KubesharkEntry>()

        logger.debug("Connecting WebSocket to {}:{}/api/wsFull", wsHost, wsPort)

        // Wrap entire WebSocket operation in a timeout to prevent indefinite hanging
        val result =
            withTimeoutOrNull(SESSION_TIMEOUT) {
                httpClient.webSocket(
                    method = HttpMethod.Get,
                    host = wsHost,
                    port = wsPort,
                    path = "/api/wsFull",
                ) {
                    // Send empty KFL filter — we filter in TrafficTransformer instead.
                    // Non-empty KFL strings like "http" are parsed as queries and may
                    // match nothing if the syntax doesn't match Kubeshark's expectations.
                    //
                    // TODO: Figure out the right KFL syntax and push HTTP protocol +
                    //  target-service filtering to the server. At high traffic, streaming
                    //  every L7 entry only to discard most of them in TrafficTransformer
                    //  wastes agent CPU (JSON parsing dominates). See Kubeshark docs for
                    //  KFL query reference — https://docs.kubeshark.co/en/filtering
                    send(Frame.Text(""))

                    try {
                        while (entries.size < limit) {
                            val frame =
                                withTimeoutOrNull(COLLECT_TIMEOUT) {
                                    incoming.receive()
                                } ?: break // No more entries within timeout

                            if (frame is Frame.Text) {
                                val entry = parseEntry(frame.readText()) ?: continue

                                // Skip entries older than cursor
                                if (startMs != null && entry.timestamp < startMs) continue

                                entries.add(entry)
                            }
                        }
                    } catch (_: ClosedReceiveChannelException) {
                        // Server closed the connection — return what we have
                    }

                    logger.debug("Collected {} entries from WebSocket", entries.size)
                }
            }

        if (result == null) {
            logger.error("WebSocket session timed out after {}", SESSION_TIMEOUT)
        }

        return entries
    }

    private fun parseEntry(text: String): KubesharkEntry? =
        try {
            json.decodeFromString<KubesharkEntry>(text)
        } catch (e: Exception) {
            logger.debug("Skipping unparseable WebSocket message: {}", e.message)
            null
        }

    companion object {
        /**
         * How long to wait for the next entry before considering the batch complete.
         * Short enough to keep the capture loop responsive, long enough to collect
         * entries that arrive in bursts.
         */
        val COLLECT_TIMEOUT: Duration = 2.seconds

        /**
         * Maximum time for the entire WebSocket session (connect + collect).
         * Prevents the capture loop from hanging indefinitely if the connection stalls.
         */
        val SESSION_TIMEOUT: Duration = 30.seconds
    }
}
