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
import java.util.concurrent.atomic.AtomicReference

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

    val kubesharkClient = KubesharkClient(httpClient, staticConfig.kubesharkUrl)
    val collectorClient =
        CollectorClient(httpClient, staticConfig.collectorUrl, staticConfig.apiKey)
    val configClient =
        ConfigClient(httpClient, staticConfig.collectorUrl, staticConfig.apiKey)
    val transformer = TrafficTransformer(dynamicConfig)

    runBlocking {
        coroutineScope {
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
        delay(dynamicConfig.get().discoveryIntervalMs)
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
        delay(dynamicConfig.get().configPollIntervalMs)
    }
}

suspend fun trafficCaptureLoop(
    dynamicConfig: AtomicReference<DynamicConfig>,
    kubesharkClient: TrafficSource,
    collectorClient: CollectorClient,
    transformer: TrafficTransformer,
) {
    var cursor: Long? = null

    while (true) {
        val config = dynamicConfig.get()
        try {
            val result =
                captureOneBatch(cursor, config.batchSize, kubesharkClient, collectorClient, transformer)
            cursor = result.cursor

            if (result.lagMs != null && result.lagMs > LAG_WARN_THRESHOLD_MS) {
                // TODO: Surface lag to the platform (e.g. via config poll or heartbeat)
                //  so the customer can take action (scale agents, increase sampling, tune batch size)
                logger.warn(
                    "Traffic capture lagging: {}ms behind real-time ({} entries this batch)",
                    result.lagMs,
                    result.entriesProcessed,
                )
            }

            if (result.caughtUp) {
                delay(config.captureIntervalMs)
            }
        } catch (e: Exception) {
            logger.error("Traffic capture failed", e)
            delay(config.captureIntervalMs)
        }
    }
}

/**
 * Result of processing a single batch from Kubeshark.
 */
data class CaptureResult(
    val cursor: Long?,
    val entriesProcessed: Int,
    val lagMs: Long?,
    val caughtUp: Boolean,
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
 * Fetch one batch from Kubeshark, transform, and send to collector.
 * No loops — the caller decides whether to continue or delay based on [CaptureResult.caughtUp].
 *
 * nowMs is injectable for testing lag detection. Defaults to wall clock.
 */
suspend fun captureOneBatch(
    cursor: Long?,
    batchSize: Int,
    trafficSource: TrafficSource,
    collectorClient: CollectorClient,
    transformer: TrafficTransformer,
    nowMs: Long = System.currentTimeMillis(),
): CaptureResult {
    val entries =
        trafficSource.listHttpCalls(
            startMs = cursor,
            limit = batchSize,
        )

    if (entries.isEmpty()) {
        return CaptureResult(
            cursor = cursor,
            entriesProcessed = 0,
            lagMs = null,
            caughtUp = true,
        )
    }

    val captured = transformer.transform(entries)

    if (captured.isNotEmpty()) {
        val sent =
            collectorClient.sendBatch(
                BatchCapturedInputRequest(items = captured),
            )
        if (sent) {
            logger.info(
                "Captured {} entries (from {} raw)",
                captured.size,
                entries.size,
            )
        }
    }

    val newCursor = entries.maxOf { it.timestamp } + 1
    val lagMs = nowMs - newCursor

    return CaptureResult(
        cursor = newCursor,
        entriesProcessed = entries.size,
        lagMs = lagMs,
        caughtUp = entries.size < batchSize,
    )
}

/** Warn when cursor is more than 15s behind wall clock */
private const val LAG_WARN_THRESHOLD_MS = 15_000L
