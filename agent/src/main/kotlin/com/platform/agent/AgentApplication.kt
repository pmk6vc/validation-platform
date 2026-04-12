package com.platform.agent

import com.platform.agent.models.BatchCapturedInputRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("AgentApplication")

fun main() {
    val staticConfig = StaticConfig.fromEnvironment()
    val dynamicConfig = AtomicReference(DynamicConfig.default())

    logger.info(
        "Starting validation agent: kubeshark={}, collector={}",
        staticConfig.kubesharkUrl,
        staticConfig.collectorUrl,
    )

    val httpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(WebSockets)
        }

    val collectorClient =
        CollectorClient(httpClient, staticConfig.collectorUrl, staticConfig.apiKey)
    val configClient =
        ConfigClient(httpClient, staticConfig.collectorUrl, staticConfig.apiKey)
    val transformer = TrafficTransformer(dynamicConfig)

    runBlocking {
        coroutineScope {
            // KubesharkClient launches its streamer in this scope; cancelling the
            // scope cancels the WebSocket session via structured concurrency.
            val kubesharkClient = KubesharkClient(httpClient, staticConfig.kubesharkUrl, this)

            launch { serviceDiscoveryLoop(dynamicConfig) }
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

suspend fun serviceDiscoveryLoop(dynamicConfig: AtomicReference<DynamicConfig>) {
    while (true) {
        try {
            discoverServices()
        } catch (e: Exception) {
            logger.error("Service discovery loop failed", e)
        }
        delay(dynamicConfig.get().discoveryInterval)
    }
}

suspend fun configPollLoop(
    configClient: ConfigClient,
    dynamicConfig: AtomicReference<DynamicConfig>,
) {
    while (true) {
        try {
            pollConfig(configClient, dynamicConfig)
        } catch (e: Exception) {
            logger.error("Config poll loop failed", e)
        }
        delay(dynamicConfig.get().configPollInterval)
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
    dynamicConfig: AtomicReference<DynamicConfig>,
    kubesharkClient: KubesharkClient,
    collectorClient: CollectorClient,
    transformer: TrafficTransformer,
) {
    while (true) {
        val config = dynamicConfig.get()
        try {
            val result =
                captureOneBatch(
                    batchSize = config.batchSize,
                    maxWait = config.captureInterval,
                    kubesharkClient = kubesharkClient,
                    collectorClient = collectorClient,
                    transformer = transformer,
                )

            touchHeartbeat()

            if (result.lag != null && result.lag > LAG_WARN_THRESHOLD) {
                // TODO: Surface lag to the platform (e.g. via config poll or heartbeat)
                //  so the customer can take action (scale agents, increase sampling, tune batch size)
                logger.warn(
                    "Traffic capture lagging: {} behind real-time ({} entries this batch)",
                    result.lag,
                    result.entriesProcessed,
                )
            }
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
 * V1 stub — service discovery will be implemented when the platform
 * exposes a service registration endpoint.
 *
 * TODO: Inject a K8s client to list services in the cluster and a
 *  platform registration client to POST /api/services. The platform
 *  returns a service ID map that populates targetServices in DynamicConfig.
 *  Until then, targetServices comes entirely from the config poll.
 */
fun discoverServices() {
    logger.debug("Service discovery: not yet implemented")
}

/**
 * Fetch config from the platform and update the shared AtomicReference.
 * Returns true if config was updated, false otherwise.
 */
suspend fun pollConfig(
    configClient: ConfigClient,
    dynamicConfig: AtomicReference<DynamicConfig>,
): Boolean {
    val newConfig = configClient.fetchConfig() ?: return false

    val oldConfig = dynamicConfig.getAndSet(newConfig)
    if (oldConfig.targetServices != newConfig.targetServices) {
        logger.info(
            "Target services updated: {}",
            newConfig.targetServices.keys,
        )
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
): CaptureResult {
    val entries = kubesharkClient.drainBatch(limit = batchSize, maxWait = maxWait)

    if (entries.isEmpty()) {
        return CaptureResult(entriesProcessed = 0, lag = null)
    }

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
