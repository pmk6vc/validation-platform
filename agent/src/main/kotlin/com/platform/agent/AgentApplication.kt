package com.platform.agent

import com.platform.agent.models.BatchCapturedInputRequest
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.compression.ContentEncodingConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("AgentApplication")

// One HttpClient per target server. Each factory installs only the plugins
// the corresponding server actually supports, so a plugin landing on (say)
// the collector factory cannot accidentally be sent to platform — the
// platform client never had it. Tests pass an HttpClientEngine (typically
// MockEngine) to the engine-taking overload; production calls the no-arg form.
//
// Adding a new client-side plugin is a one-place edit on the relevant
// factory; everything that uses that factory inherits the change.

/** Client for talking to the platform server (e.g. GET /api/agent/config). */
fun buildAgentPlatformHttpClient(): HttpClient = HttpClient(CIO) { configurePlatform() }

fun buildAgentPlatformHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) { configurePlatform() }

private fun HttpClientConfig<*>.configurePlatform() {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

/** Client for talking to the collector server (POST /api/captured-inputs). */
fun buildAgentCollectorHttpClient(): HttpClient = HttpClient(CIO) { configureCollector() }

fun buildAgentCollectorHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) { configureCollector() }

private fun HttpClientConfig<*>.configureCollector() {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    // Compress outbound request bodies with gzip. The collector installs
    // Ktor's Compression plugin to decompress incoming Content-Encoding: gzip
    // requests. Scoped to this factory: only the agent → collector path
    // gets gzip; platform and Kubeshark clients are unaffected.
    // NOTE: HTTP/2 (transport-layer multiplexing + framing) is a separate
    // concern requiring non-trivial engine config; gzip alone delivers
    // most of the wire-size benefit for these workloads.
    install(ContentEncoding) {
        // Default mode is DecompressResponse only; flip to CompressRequest so
        // the AfterRenderHook actually compresses bodies. Each call site still
        // has to opt in via request.compress("gzip"); CollectorClient does
        // that on every POST (collector responses are tiny JSON ack bodies
        // that don't benefit from response decompression).
        mode = ContentEncodingConfig.Mode.CompressRequest
        gzip()
    }
}

/** Client for the Kubeshark WebSocket session. WebSockets only. */
fun buildAgentKubesharkHttpClient(): HttpClient = HttpClient(CIO) { configureKubeshark() }

fun buildAgentKubesharkHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) { configureKubeshark() }

private fun HttpClientConfig<*>.configureKubeshark() {
    install(WebSockets) {
        // Send WebSocket pings every 30s. Without this, a silently-dead
        // connection (NAT idle timeout, kubeshark-front hung, peer process
        // gone without FIN/RST) leaves the streamer suspended in
        // `for (frame in incoming)` indefinitely — `connected` stays true,
        // the heartbeat keeps firing, and the liveness probe is fooled.
        // A missed pong throws, the for-loop exits, the finally clears
        // `connected`, and the reconnect path takes over.
        pingIntervalMillis = 30_000
    }
}

fun main() {
    val staticConfig = StaticConfig.fromEnvironment()
    val dynamicConfig = MutableStateFlow(DynamicConfig.default())

    logger.info(
        "Starting validation agent: kubeshark={}, platform={}, collector={}",
        staticConfig.kubesharkUrl,
        staticConfig.platformUrl,
        staticConfig.collectorUrl,
    )

    val platformHttpClient = buildAgentPlatformHttpClient()
    val collectorHttpClient = buildAgentCollectorHttpClient()
    val kubesharkHttpClient = buildAgentKubesharkHttpClient()

    val collectorClient =
        CollectorClient(collectorHttpClient, staticConfig.collectorUrl, staticConfig.apiKey)
    val configClient =
        ConfigClient(platformHttpClient, staticConfig.platformUrl, staticConfig.apiKey)
    val platformClient =
        PlatformClient(platformHttpClient, staticConfig.platformUrl, staticConfig.apiKey)
    val k8sDiscovery = K8sServiceDiscovery()
    val transformer = TrafficTransformer(dynamicConfig)

    runBlocking {
        coroutineScope {
            // KubesharkClient observes dynamicConfig via StateFlow and
            // reconnects automatically when targetServices changes.
            val kubesharkClient =
                KubesharkClient(
                    httpClient = kubesharkHttpClient,
                    baseUrl = staticConfig.kubesharkUrl,
                    scope = this,
                    configFlow = dynamicConfig,
                )

            launch { serviceDiscoveryLoop(k8sDiscovery, platformClient, dynamicConfig) }
            launch { configPollLoop(configClient, dynamicConfig) }
            launch {
                trafficCaptureLoop(
                    dynamicConfig,
                    kubesharkClient,
                    collectorClient,
                    transformer,
                )
            }
        }
    }
}

// --- Loop wrappers (while/true + delay + error handling) ---

suspend fun serviceDiscoveryLoop(
    discovery: K8sServiceDiscovery,
    platformClient: PlatformClient,
    dynamicConfig: StateFlow<DynamicConfig>,
    registeredServices: MutableSet<Pair<String, String>> = mutableSetOf(),
    permanentlyFailed: MutableSet<Pair<String, String>> = mutableSetOf(),
) {
    while (true) {
        try {
            discoverServices(
                discovery,
                platformClient,
                dynamicConfig,
                registeredServices,
                permanentlyFailed,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Service discovery loop failed", e)
        }
        delay(dynamicConfig.value.discoveryInterval)
    }
}

suspend fun configPollLoop(
    configClient: ConfigClient,
    dynamicConfig: MutableStateFlow<DynamicConfig>,
) {
    while (true) {
        try {
            pollConfig(configClient, dynamicConfig)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Config poll loop failed", e)
        }
        delay(dynamicConfig.value.configPollInterval)
    }
}

/**
 * Drain batches from [KubesharkClient]'s channel and forward them to the
 * collector. No per-iteration sleep is needed — when there is no traffic,
 * [KubesharkClient.drainBatch] waits up to [DynamicConfig.captureInterval]
 * for the first entry, so an idle agent naturally idles.
 *
 * The only `delay` here is after a transient error (e.g. collector unreachable)
 * to avoid tight-looping on a persistent failure.
 */
suspend fun trafficCaptureLoop(
    dynamicConfig: StateFlow<DynamicConfig>,
    kubesharkClient: KubesharkClient,
    collectorClient: CollectorClient,
    transformer: TrafficTransformer,
) {
    while (true) {
        val config = dynamicConfig.value
        try {
            val result =
                captureOneBatch(
                    batchSize = config.batchSize,
                    maxWait = config.captureInterval,
                    kubesharkClient = kubesharkClient,
                    collectorClient = collectorClient,
                    transformer = transformer,
                )

            if (result.lag != null && result.lag > LAG_WARN_THRESHOLD) {
                // TODO: Surface lag to the platform (e.g. via config poll or heartbeat)
                //  so the customer can take action (scale agents, increase sampling, tune batch size)
                logger.warn(
                    "Traffic capture lagging: {} behind real-time ({} entries this batch)",
                    result.lag,
                    result.entriesProcessed,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Traffic capture failed", e)
            delay(config.captureInterval)
        }
    }
}

/**
 * Result of processing a single batch from Kubeshark.
 */
data class CaptureResult(
    val entriesProcessed: Int,
    val lag: Duration?,
)

// --- Single-iteration logic (testable without loops) ---

/**
 * One iteration of Loop 1.
 *
 * Lists Services from the K8s API (scoped by [DynamicConfig.namespaceFilters]),
 * diffs against [registeredServices] AND [permanentlyFailed], and POSTs each
 * remaining service to the platform's `/api/services`. The platform's
 * `GET /api/agent/config` will then include the registered services in
 * `targetServices` on the next config poll, and the existing
 * `MutableStateFlow<DynamicConfig>` propagates them to [KubesharkClient]
 * (which reconnects with an updated KFL query).
 *
 * Outcome handling:
 * - [RegistrationOutcome.Success] → add to [registeredServices]; never re-POST.
 * - [RegistrationOutcome.PermanentRejection] → add to [permanentlyFailed];
 *   never re-POST. The platform rejected the request shape (e.g. invalid name)
 *   and retrying won't change the answer.
 * - [RegistrationOutcome.TransientFailure] → leave alone. The next discovery
 *   tick will re-attempt registration.
 *
 * Both sets are in-memory and per-pod; restarting the agent re-POSTs
 * everything, but the platform's 409-on-conflict makes that idempotent. This
 * is intentional — there's no value in persisting registration state across
 * restarts when the platform is the source of truth.
 */
suspend fun discoverServices(
    discovery: K8sServiceDiscovery,
    platformClient: PlatformClient,
    dynamicConfig: StateFlow<DynamicConfig>,
    registeredServices: MutableSet<Pair<String, String>>,
    permanentlyFailed: MutableSet<Pair<String, String>> = mutableSetOf(),
) {
    val found = discovery.discover(dynamicConfig.value.namespaceFilters)
    val candidates =
        found.filter {
            val key = it.namespace to it.name
            key !in registeredServices && key !in permanentlyFailed
        }
    if (candidates.isEmpty()) {
        logger.debug(
            "Service discovery: no new services (registered: {}, permanently failed: {})",
            registeredServices.size,
            permanentlyFailed.size,
        )
        return
    }
    for (svc in candidates) {
        when (platformClient.registerService(svc.namespace, svc.name)) {
            RegistrationOutcome.Success -> {
                registeredServices += svc.namespace to svc.name
                logger.info("Registered service {}/{}", svc.namespace, svc.name)
            }
            RegistrationOutcome.PermanentRejection -> {
                permanentlyFailed += svc.namespace to svc.name
                logger.warn(
                    "Service {}/{} permanently rejected by platform; will not re-attempt",
                    svc.namespace,
                    svc.name,
                )
            }
            RegistrationOutcome.TransientFailure -> {
                // Leave svc out of both sets so the next tick re-attempts.
                // Already logged with details inside PlatformClient.registerService.
            }
        }
    }
}

/**
 * Fetch config from the platform and emit it to [dynamicConfig].
 *
 * Consumers (KubesharkClient, TrafficTransformer, capture loop) observe
 * the StateFlow and react to changes independently.
 *
 * Returns true if config was updated, false otherwise (e.g. platform returned
 * an error or is unreachable — old config is preserved in that case).
 */
suspend fun pollConfig(
    configClient: ConfigClient,
    dynamicConfig: MutableStateFlow<DynamicConfig>,
): Boolean {
    val newConfig = configClient.fetchConfig() ?: return false

    val oldConfig = dynamicConfig.value
    dynamicConfig.value = newConfig

    if (oldConfig.targetServices != newConfig.targetServices) {
        logger.info("Target services updated: {}", newConfig.targetServices.keys)
    }
    if (oldConfig.samplingRate != newConfig.samplingRate) {
        logger.info(
            "Sampling rate changed: {} -> {}",
            oldConfig.samplingRate,
            newConfig.samplingRate,
        )
    }
    return true
}

/**
 * Drain one batch from Kubeshark, transform, and send to collector.
 *
 * The [KubesharkClient] maintains a persistent WebSocket session with an
 * internal bounded channel. [KubesharkClient.drainBatch] pulls up to
 * [batchSize] entries from that channel, waiting up to [maxWait] for the
 * first entry. This means there's no inter-batch sleep needed in the capture
 * loop: an idle agent is already sleeping inside `drainBatch`.
 *
 * Lag is computed from the drained batch's newest entry. If the channel has
 * been filling up because the capture loop is falling behind, the newest
 * entry in a drained batch will still be recent-ish — but its age against
 * wall clock tells us how far behind live traffic we are.
 *
 * nowMs is injectable for testing lag detection. Defaults to wall clock.
 */
suspend fun captureOneBatch(
    batchSize: Int,
    maxWait: Duration,
    kubesharkClient: KubesharkClient,
    collectorClient: CollectorClient,
    transformer: TrafficTransformer,
    nowMs: Long = System.currentTimeMillis(),
    heartbeat: () -> Unit = ::touchHeartbeat,
    isKubesharkConnected: () -> Boolean = kubesharkClient::isConnected,
): CaptureResult {
    val entries = kubesharkClient.drainBatch(limit = batchSize, maxWait = maxWait)

    if (entries.isEmpty()) {
        // Empty drain has two causes: legitimate idle (WebSocket open, no
        // traffic right now) vs broken session (reconnect loop, channel
        // empty). Heartbeat the former so the probe doesn't restart a
        // healthy quiet pod; skip the latter so the probe fails and the
        // pod restarts.
        if (isKubesharkConnected()) {
            heartbeat()
        }
        return CaptureResult(entriesProcessed = 0, lag = null)
    }

    // Heartbeat fires on successful drain — i.e. Kubeshark is delivering entries.
    // Touched before the collector send so a collector outage doesn't kill the
    // pod's liveness probe (sendBatch retries indefinitely on transient errors).
    heartbeat()

    val captured = transformer.transform(entries)

    if (captured.isNotEmpty()) {
        // sendBatch retries transient failures with exponential backoff and
        // suspends until the POST succeeds (or a 4xx permanent failure drops
        // the batch). A sustained collector outage therefore backpressures
        // this entire call, which stops the capture loop from draining the
        // Kubeshark channel — the right behavior.
        collectorClient.sendBatch(BatchCapturedInputRequest(items = captured))
        logger.info(
            "Captured {} entries (from {} raw)",
            captured.size,
            entries.size,
        )
    }

    val maxTimestamp = entries.maxOf { it.timestamp }
    val lag: Duration = (nowMs - maxTimestamp).milliseconds

    return CaptureResult(
        entriesProcessed = entries.size,
        lag = lag,
    )
}

/** Warn when the newest drained entry is more than this behind wall clock */
private val LAG_WARN_THRESHOLD: Duration = 15.seconds

/** Liveness probe heartbeat file. Path must match the liveness probe in k8s/agent/agent.yaml. */
val HEARTBEAT_FILE: File = File("/tmp/agent-alive")

fun touchHeartbeat() {
    HEARTBEAT_FILE.writeText(System.currentTimeMillis().toString())
}
