package com.platform.agent

import com.platform.agent.models.KubesharkEntry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Client that maintains a long-lived WebSocket session to Kubeshark's
 * `/api/wsFull` endpoint and buffers incoming entries into a bounded [Channel]
 * for downstream consumption.
 *
 * **Transport:** Kubeshark v53+ serves traffic exclusively over WebSocket.
 * Requests hit `kubeshark-front` (an nginx proxy in the `default` namespace)
 * at `/api/wsFull`, which rewrites and forwards to `kubeshark-hub` with proper
 * WebSocket upgrade headers.
 *
 * **Why persistent?** Each fresh WebSocket session re-delivers ~4–10s of
 * recent-history backlog before reaching live entries (measured 2026-04-11).
 * A connect-per-poll design would re-parse and discard ~300 entries per
 * reconnect at moderate traffic. A single persistent session pays the backlog
 * cost once at startup, then receives live entries indefinitely.
 *
 * **Backpressure:** The channel is bounded (default 1000 entries). If the
 * capture loop can't drain fast enough, [Channel.send] suspends the streamer,
 * which stops reading WebSocket frames, which fills Ktor's receive buffer,
 * which closes the TCP window, which slows Kubeshark's emission. If Kubeshark's
 * own buffers also fill, it drops on its side. The agent never OOMs.
 *
 * **Lifecycle:** The client launches its streamer coroutine in the [scope]
 * passed to the constructor. When that scope is cancelled, the streamer and
 * the WebSocket session are cancelled with it. No explicit shutdown is needed.
 *
 * **Filtering:** The client sends an empty KFL filter on connect, so Kubeshark
 * streams every L7 entry. Protocol and target-service filtering is handled
 * client-side in [TrafficTransformer].
 *
 * TODO: Figure out the right KFL syntax and push HTTP protocol + target-service
 *  filtering to the server. At high traffic, streaming every L7 entry only to
 *  discard most of them wastes agent CPU (JSON parsing dominates). See
 *  https://docs.kubeshark.co/en/filtering for KFL reference.
 */
class KubesharkClient(
    private val httpClient: HttpClient,
    baseUrl: String,
    scope: CoroutineScope,
    capacity: Int = DEFAULT_CHANNEL_CAPACITY,
    private val reconnectDelay: Duration = DEFAULT_RECONNECT_DELAY,
) {
    private val logger = LoggerFactory.getLogger(KubesharkClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    // Parse connection details once at construction
    private val parsedUri = URI.create(baseUrl)
    private val wsHost: String = parsedUri.host
    private val wsPort: Int = if (parsedUri.port > 0) parsedUri.port else 80

    /**
     * Bounded channel between the background streamer (producer) and the
     * capture loop (consumer). Suspending `send` when full applies natural
     * backpressure through the TCP stack.
     */
    private val channel = Channel<KubesharkEntry>(capacity = capacity)

    /**
     * Maximum entry timestamp seen so far. Used with [DEDUP_LOOKBACK] as a
     * simple sliding-window dedup filter: any entry with `ts < (lastSeen - lookback)`
     * is discarded as a duplicate (from Kubeshark's ~4-10s reconnect history
     * replay), while entries within the lookback window are accepted to
     * preserve in-session out-of-order traffic.
     *
     * Only read/written from the single streamer coroutine, but marked
     * `@Volatile` to make the memory-model behavior explicit and allow
     * future observability hooks to read it from other threads.
     *
     * Trade-off vs an ID-based LRU cache:
     *   - Constant memory (one Long) — no sizing, no hidden failure at high rates
     *   - Predictable degradation: ~`lookback × arrival_rate` dupes slip through
     *     per reconnect (a rare event)
     *   - Drops in-session entries whose regression exceeds the lookback
     *     (measured on our test cluster: max regression 4.8s, so 5s covers all)
     */
    @Volatile
    private var lastSeenTimestamp: Long = 0L

    /** Handle to the background streamer, exposed for test/shutdown introspection. */
    val streamerJob: Job = scope.launch { streamerLoop() }

    /**
     * Drain up to [limit] entries from the channel.
     *
     * Waits up to [maxWait] for the first entry; subsequent entries are pulled
     * non-blocking via [Channel.tryReceive]. If no entry arrives within
     * [maxWait], returns an empty list (the capture loop treats this as
     * "no new traffic right now").
     *
     * This is the only external API. Callers do not need to know about the
     * underlying WebSocket session — they just ask for batches.
     */
    suspend fun drainBatch(
        limit: Int,
        maxWait: Duration,
    ): List<KubesharkEntry> {
        val entries = mutableListOf<KubesharkEntry>()

        val first =
            withTimeoutOrNull(maxWait) {
                channel.receive()
            } ?: return emptyList()
        entries.add(first)

        while (entries.size < limit) {
            val next = channel.tryReceive().getOrNull() ?: break
            entries.add(next)
        }

        return entries
    }

    /**
     * Long-lived streamer loop. Maintains a single WebSocket session for the
     * lifetime of the parent scope. On session failure or clean server close,
     * waits [RECONNECT_DELAY] and reconnects. Exits only when the parent
     * scope is cancelled.
     */
    private suspend fun streamerLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                runSession()
                // Clean close from server — reconnect after a short delay
                logger.info("Kubeshark WebSocket closed by server, reconnecting in {}", reconnectDelay)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Kubeshark WebSocket session failed, reconnecting in {}", reconnectDelay, e)
            }
            delay(reconnectDelay)
        }
    }

    /**
     * Run one WebSocket session. Opens the connection, sends the empty KFL
     * filter, and forwards every text frame (parsed as [KubesharkEntry]) into
     * the channel. Returns when the server closes the stream (`incoming`
     * iteration ends) or throws on error.
     */
    private suspend fun runSession() {
        logger.info("Connecting to Kubeshark WebSocket at {}:{}/api/wsFull", wsHost, wsPort)
        httpClient.webSocket(
            method = HttpMethod.Get,
            host = wsHost,
            port = wsPort,
            path = "/api/wsFull",
        ) {
            // Send empty KFL filter — non-empty strings like "http" are parsed as
            // queries and the hub will silently stream nothing if the syntax is
            // wrong. Empty = "no filter, send everything"; TrafficTransformer
            // filters client-side. See class-level TODO for server-side filtering.
            send(Frame.Text(""))
            logger.info("Kubeshark WebSocket session open, streaming entries")

            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val entry = parseEntry(frame.readText()) ?: continue
                if (!acceptAndTrack(entry.timestamp)) continue
                channel.send(entry)
            }
        }
    }

    private fun parseEntry(text: String): KubesharkEntry? =
        try {
            json.decodeFromString<KubesharkEntry>(text)
        } catch (e: Exception) {
            logger.debug("Skipping unparseable WebSocket message: {}", e.message)
            null
        }

    /**
     * Decide whether to forward an entry with the given timestamp, and update
     * [lastSeenTimestamp] if the entry is kept.
     *
     * An entry is dropped if its timestamp is more than [DEDUP_LOOKBACK] behind
     * [lastSeenTimestamp] — this is the sliding-window dedup that filters
     * reconnect history replay. Otherwise the entry is accepted and
     * [lastSeenTimestamp] is advanced if the new entry is newer.
     *
     * Returns `true` if the caller should forward the entry, `false` to drop.
     */
    private fun acceptAndTrack(timestamp: Long): Boolean {
        val floor = lastSeenTimestamp - DEDUP_LOOKBACK.inWholeMilliseconds
        if (timestamp < floor) return false
        if (timestamp > lastSeenTimestamp) {
            lastSeenTimestamp = timestamp
        }
        return true
    }

    companion object {
        /**
         * Default backoff before reconnecting after a session fails or the
         * server closes the connection. Tests can override this via the
         * constructor parameter to avoid slow reconnect waits.
         */
        val DEFAULT_RECONNECT_DELAY: Duration = 5.seconds

        /**
         * Default bounded channel capacity. At ~4KB per entry average, 1000
         * entries ≈ 4MB — comfortable within the agent's 128Mi memory limit.
         */
        const val DEFAULT_CHANNEL_CAPACITY = 1000

        /**
         * Sliding-window lookback for timestamp-based dedup.
         *
         * In-session out-of-order jitter measured on our test cluster: p50
         * 8ms, p95 1.8s, p99 3s, max 4.8s. A 5s lookback captures 100% of
         * observed jitter with safety margin.
         *
         * Trade-off: on reconnect, the first ~`lookback × arrival_rate`
         * duplicate entries (the portion of Kubeshark's history replay that
         * falls within the lookback window) will slip through and get
         * double-counted. At 75 entries/sec and 5s lookback, that's up to
         * ~375 dupes per reconnect — acceptable, because reconnects are rare
         * (hub restart, network blip) while data loss on normal in-session
         * out-of-order would happen every second.
         */
        val DEDUP_LOOKBACK: Duration = 5.seconds
    }
}
