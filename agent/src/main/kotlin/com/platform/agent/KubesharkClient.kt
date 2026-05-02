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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Persistent WebSocket client for Kubeshark's `/api/wsFull` endpoint.
 *
 * Maintains a single long-lived session (reconnects re-deliver ~4-10s of history,
 * so we avoid connect-per-poll). Entries are buffered into a bounded [Channel];
 * backpressure propagates through TCP when the channel is full.
 *
 * A KFL query is sent as the first frame to filter server-side. The client
 * observes [configFlow] and forces an immediate reconnect when `targetServices`
 * changes (Kubeshark doesn't support mid-session filter changes).
 * [TrafficTransformer] filters client-side as a safety net during the brief
 * reconnect window.
 *
 * Lifecycle is tied to [scope] — cancelling it tears down the session.
 */
class KubesharkClient(
    private val httpClient: HttpClient,
    baseUrl: String,
    scope: CoroutineScope,
    configFlow: StateFlow<DynamicConfig>,
    capacity: Int = DEFAULT_CHANNEL_CAPACITY,
    private val reconnectDelay: Duration = DEFAULT_RECONNECT_DELAY,
) {
    private val logger = LoggerFactory.getLogger(KubesharkClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val parsedUri = URI.create(baseUrl)
    private val wsHost: String = parsedUri.host
    private val wsPort: Int = if (parsedUri.port > 0) parsedUri.port else 80

    private val kflQueryRef = AtomicReference(buildKflQuery(configFlow.value.targetServices))

    /** Current session job — cancelled to force reconnect on config change. */
    private val sessionJobRef = AtomicReference<Job?>(null)

    private val channel = Channel<KubesharkEntry>(capacity = capacity)

    /**
     * Sliding-window dedup: entries older than [DEDUP_LOOKBACK] behind [lastSeenTimestamp]
     * are dropped (reconnect-replay noise). Entries within the window are kept to
     * preserve in-session out-of-order traffic. Only accessed from the streamer coroutine.
     */
    private var lastSeenTimestamp: Long = 0L

    val streamerJob: Job = scope.launch { streamerLoop() }

    /** Watches [configFlow] for targetServices changes and forces a reconnect. */
    val configWatcherJob: Job =
        scope.launch {
            configFlow
                .map { it.targetServices }
                .distinctUntilChanged()
                .collect { targetServices ->
                    val newQuery = buildKflQuery(targetServices)
                    val previous = kflQueryRef.getAndSet(newQuery)
                    if (previous != newQuery) {
                        logger.info("KFL query changed, forcing WebSocket reconnect: {}", newQuery)
                        sessionJobRef.get()?.cancel()
                    }
                }
        }

    /**
     * Drain up to [limit] entries. Waits up to [maxWait] for the first entry;
     * subsequent entries are pulled non-blocking. Returns empty if nothing arrives.
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

    /** Reconnect loop: runs a session, waits [reconnectDelay] on failure/cancel, repeats. */
    private suspend fun streamerLoop() {
        while (currentCoroutineContext().isActive) {
            // supervisorScope absorbs child cancellation (from updateKflQuery)
            // without killing this loop.
            supervisorScope {
                val job =
                    launch {
                        try {
                            runSession()
                            logger.info("Kubeshark WebSocket closed by server, reconnecting in {}", reconnectDelay)
                        } catch (_: CancellationException) {
                            logger.info(
                                "WebSocket session cancelled (query change), reconnecting in {}",
                                reconnectDelay,
                            )
                        } catch (e: Exception) {
                            logger.error("Kubeshark WebSocket session failed, reconnecting in {}", reconnectDelay, e)
                        }
                    }
                sessionJobRef.set(job)
            }
            delay(reconnectDelay)
        }
    }

    /** Open one WebSocket session: send KFL query, then stream entries into the channel. */
    private suspend fun runSession() {
        val kflQuery = kflQueryRef.get()
        logger.info("Connecting to Kubeshark WebSocket at {}:{}/api/wsFull", wsHost, wsPort)
        httpClient.webSocket(
            method = HttpMethod.Get,
            host = wsHost,
            port = wsPort,
            path = "/api/wsFull",
        ) {
            // First frame = KFL query. Empty string means "no filter".
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

    /** Accept entry if within [DEDUP_LOOKBACK] of [lastSeenTimestamp]; advance timestamp if newer. */
    private fun acceptAndTrack(timestamp: Long): Boolean {
        val floor = lastSeenTimestamp - DEDUP_LOOKBACK.inWholeMilliseconds
        if (timestamp < floor) return false
        if (timestamp > lastSeenTimestamp) {
            lastSeenTimestamp = timestamp
        }
        return true
    }

    companion object {
        val DEFAULT_RECONNECT_DELAY: Duration = 5.seconds

        /** ~4KB per entry × 1000 ≈ 4MB — within the agent's 128Mi limit. */
        const val DEFAULT_CHANNEL_CAPACITY = 1000

        /**
         * Dedup lookback window. Covers observed in-session jitter (max 4.8s)
         * with margin. On reconnect, up to ~`lookback × arrival_rate` dupes may
         * slip through — acceptable since reconnects are rare.
         */
        val DEDUP_LOOKBACK: Duration = 5.seconds

        /** Sentinel KFL filter that won't match any real K8s Service name. */
        private const val KFL_NO_MATCH = """http and dst.name == "__validation_agent_no_match__""""

        private val kflQueryLogger = LoggerFactory.getLogger("KubesharkClient.buildKflQuery")

        /**
         * A name is unsafe to embed inside a quoted KFL literal if it contains a closing
         * quote (would terminate the literal early), a backslash (KFL's escape semantics
         * are not contractual — we don't assume), or control characters that could
         * affect framing. This is *not* a name-validity check — the platform owns "is
         * this a legal service name?". The agent only owns "can I embed this string
         * without changing query semantics?".
         */
        private fun isKflSafeToEmbed(name: String): Boolean =
            name.none { c ->
                c == '"' || c == '\\' || c.isISOControl()
            }

        /**
         * Build a KFL query: `http` alone (empty map) or
         * `http and dst.name == "svc"` / `http and (dst.name == "a" or dst.name == "b")`.
         *
         * Empty map returns `"http"` (not empty string, which means "no filter").
         * Only map keys (K8s service names) are used; values (platform IDs) are ignored.
         *
         * Names that can't be safely embedded as a quoted KFL literal are dropped
         * with a warning. If every name is rejected, returns a no-match sentinel
         * rather than the unfiltered `"http"` query — refusing to widen capture
         * beyond what was requested.
         */
        fun buildKflQuery(targetServices: Map<String, String>): String {
            if (targetServices.isEmpty()) return "http"

            val safeNames =
                targetServices.keys.filter { name ->
                    if (isKflSafeToEmbed(name)) {
                        true
                    } else {
                        kflQueryLogger.warn(
                            "Dropping service name from KFL query — cannot be safely embedded: {}",
                            name,
                        )
                        false
                    }
                }

            if (safeNames.isEmpty()) return KFL_NO_MATCH

            val dstFilter =
                if (safeNames.size == 1) {
                    """dst.name == "${safeNames[0]}""""
                } else {
                    safeNames.joinToString(
                        separator = " or ",
                        prefix = "(",
                        postfix = ")",
                    ) { name -> """dst.name == "$name"""" }
                }

            return "http and $dstFilter"
        }
    }
}
