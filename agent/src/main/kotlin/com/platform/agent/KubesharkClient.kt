package com.platform.agent

import com.platform.agent.models.KubesharkEntry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Client that connects to Kubeshark's WebSocket /wsFull endpoint to receive
 * real-time L7 API call entries.
 *
 * Kubeshark v53+ exposes traffic data exclusively over WebSocket — there is
 * no REST API for querying entries. The client sends a KFL (Kubeshark Filter
 * Language) filter string on connect and receives a stream of JSON entries.
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
) : TrafficSource {
    private val logger = LoggerFactory.getLogger(KubesharkClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetch HTTP API calls from Kubeshark via WebSocket.
     *
     * Opens a WebSocket to /wsFull, sends the "http" KFL filter, and collects
     * entries until [limit] is reached or no new entry arrives within the
     * collect timeout window.
     *
     * @param startMs Unix timestamp in milliseconds — only return entries after this time
     * @param limit Maximum number of entries to return
     * @return List of Kubeshark entries (unfiltered — caller handles service filtering)
     */
    override suspend fun listHttpCalls(
        startMs: Long?,
        limit: Int,
    ): List<KubesharkEntry> =
        try {
            collectEntries(startMs, limit)
        } catch (e: Exception) {
            logger.warn("Kubeshark WebSocket error: {}", e.message)
            emptyList()
        }

    private suspend fun collectEntries(
        startMs: Long?,
        limit: Int,
    ): List<KubesharkEntry> {
        val entries = mutableListOf<KubesharkEntry>()

        // Convert http:// to ws:// for WebSocket connection
        val wsUrl =
            baseUrl
                .replace("http://", "ws://")
                .replace("https://", "wss://")

        coroutineScope {
            httpClient.webSocket("$wsUrl/api/wsFull") {
                // Send KFL filter to start receiving HTTP traffic
                send(Frame.Text("http"))

                // Collect entries from the stream
                val done = Channel<Unit>(1)

                val collector =
                    launch {
                        try {
                            while (entries.size < limit) {
                                val frame =
                                    withTimeoutOrNull(COLLECT_TIMEOUT_MS) {
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
                        } finally {
                            done.trySend(Unit)
                        }
                    }

                done.receive()
                collector.cancel()
            }
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
        const val COLLECT_TIMEOUT_MS = 2_000L
    }
}
