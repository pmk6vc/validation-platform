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
import java.util.concurrent.atomic.AtomicReference
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
 * **KFL filtering:** The client sends a KFL query as the first WebSocket text
 * frame. Kubeshark hub parses the query and only streams matching entries,
 * reducing the volume of JSON the agent must parse and discard.
 *
 * The initial query is [buildKflQuery] of whatever [targetServices] are known
 * at construction. When target services change (config poll), callers invoke
 * [updateKflQuery] with the new query string. The updated query takes effect
 * on the next reconnect — changing the filter mid-session is not supported by
 * the Kubeshark protocol, so we rely on the persistent-session reconnect cycle
 * to pick up the change.
 *
 * **Why not reconnect immediately on filter change?** Reconnects pay the
 * 4–10s history-replay cost. If `targetServices` changes infrequently (every
 * ~60s config poll), waiting for a natural reconnect is fine. The client-side
 * filter in [TrafficTransformer] continues to dedup entries that Kubeshark
 * streams between the config change and the next reconnect.
 *
 * **Safe KFL syntax (validated 2026-04-11):** An empty string means "no
 * filter, stream everything". The `http` keyword limits the stream to HTTP
 * entries. `dst.name == "svc"` limits to a specific destination service.
 * Non-empty strings that fail KFL parsing cause the hub to silently stream
 * nothing — use [buildKflQuery] rather than constructing raw strings.
 */
class KubesharkClient(
    private val httpClient: HttpClient,
    baseUrl: String,
    scope: CoroutineScope,
    capacity: Int = DEFAULT_CHANNEL_CAPACITY,
    private val reconnectDelay: Duration = DEFAULT_RECONNECT_DELAY,
    initialKflQuery: String = "",
) {
    private val logger = LoggerFactory.getLogger(KubesharkClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    // Parse connection details once at construction
    private val parsedUri = URI.create(baseUrl)
    private val wsHost: String = parsedUri.host
    private val wsPort: Int = if (parsedUri.port > 0) parsedUri.port else 80

    /**
     * The KFL query sent to Kubeshark at the start of each WebSocket session.
     * Updated atomically by [updateKflQuery]; the new query takes effect on the
     * next reconnect. Each [runSession] reads this once on entry.
     */
    private val kflQueryRef = AtomicReference(initialKflQuery)

    /**
     * Replace the KFL query used for future WebSocket sessions.
     *
     * The change takes effect the next time the streamer reconnects (e.g. after
     * a server-side close or a network failure). Until then, the old session
     * continues streaming under the old filter — but [TrafficTransformer]
     * continues to filter client-side as a safety net, so no spurious entries
     * reach the collector during the transition window.
     *
     * Calling this with the same query that is already set is a no-op with
     * respect to reconnect behavior.
     */
    fun updateKflQuery(query: String) {
        kflQueryRef.set(query)
    }

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
     * Only read/written from the single streamer coroutine.
     *
     * Trade-off vs an ID-based LRU cache:
     *   - Constant memory (one Long) — no sizing, no hidden failure at high rates
     *   - Predictable degradation: ~`lookback × arrival_rate` dupes slip through
     *     per reconnect (a rare event)
     *   - Drops in-session entries whose regression exceeds the lookback
     *     (measured on our test cluster: max regression 4.8s, so 5s covers all)
     */
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
     * Run one WebSocket session. Opens the connection, sends the current KFL
     * query as the first text frame, then forwards every subsequent text frame
     * (parsed as [KubesharkEntry]) into the channel. Returns when the server
     * closes the stream (`incoming` iteration ends) or throws on error.
     *
     * The KFL query is read once from [kflQueryRef] at session start; changes
     * made via [updateKflQuery] take effect on the next reconnect.
     */
    private suspend fun runSession() {
        val kflQuery = kflQueryRef.get()
        logger.info("Connecting to Kubeshark WebSocket at {}:{}/api/wsFull", wsHost, wsPort)
        httpClient.webSocket(
            method = HttpMethod.Get,
            host = wsHost,
            port = wsPort,
            path = "/api/wsFull",
        ) {
            // Send the KFL query as the first frame. Kubeshark hub parses it and
            // streams only matching entries. An empty string means "no filter,
            // stream everything". The TrafficTransformer still filters client-side
            // as a safety net for the window between a config change and the next
            // reconnect.
            send(Frame.Text(kflQuery))
            logger.info(
                "Kubeshark WebSocket session open, KFL filter: {}",
                kflQuery.ifEmpty { "(none — streaming all)" },
            )

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

        /**
         * Build a KFL query string that limits Kubeshark to HTTP entries
         * destined for the given target services.
         *
         * KFL syntax (validated against Kubeshark docs):
         *   - `http` — bare keyword, filters to HTTP protocol only
         *   - `dst.name == "svc"` — matches entries where the destination
         *     pod/service name equals the given string
         *   - Multiple services are combined with `or` inside parentheses
         *
         * Examples:
         * ```
         * buildKflQuery(emptyMap())
         *   → "http"
         *
         * buildKflQuery(mapOf("order-service" to "svc-1"))
         *   → "http and dst.name == \"order-service\""
         *
         * buildKflQuery(mapOf("order-service" to "svc-1", "api-gateway" to "svc-2"))
         *   → "http and (dst.name == \"order-service\" or dst.name == \"api-gateway\")"
         * ```
         *
         * When [targetServices] is empty we emit `http` rather than an empty
         * string. An empty string means "no filter" in KFL (stream everything),
         * but when the agent has no target services configured it should still
         * restrict to HTTP to reduce the parsing load — [TrafficTransformer]'s
         * destination filter will then drop everything until services appear.
         *
         * @param targetServices K8s service name → platform service ID map
         *   (only the keys matter for the KFL query)
         */
        fun buildKflQuery(targetServices: Map<String, String>): String {
            if (targetServices.isEmpty()) return "http"

            val serviceNames = targetServices.keys.toList()
            val dstFilter =
                if (serviceNames.size == 1) {
                    """dst.name == "${serviceNames[0]}""""
                } else {
                    serviceNames.joinToString(
                        separator = " or ",
                        prefix = "(",
                        postfix = ")",
                    ) { name -> """dst.name == "$name"""" }
                }

            return "http and $dstFilter"
        }
    }
}
