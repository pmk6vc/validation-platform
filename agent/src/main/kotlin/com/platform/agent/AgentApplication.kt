package com.platform.agent

import com.platform.agent.models.BatchCapturedInputRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
    kubesharkClient: KubesharkClient,
    collectorClient: CollectorClient,
    transformer: TrafficTransformer,
) {
    var cursor: Long? = null

    while (true) {
        val config = dynamicConfig.get()
        try {
            val result = captureTraffic(cursor, config.batchSize, kubesharkClient, collectorClient, transformer)
            cursor = result.cursor
        } catch (e: Exception) {
            logger.error("Traffic capture loop failed", e)
        }
        delay(config.captureIntervalMs)
    }
}

/**
 * Result of a single capture cycle. Carries the updated cursor
 * and metrics the caller uses for lag detection.
 */
data class CaptureResult(
    val cursor: Long?,
    val pagesProcessed: Int,
    val entriesProcessed: Int,
    val lagMs: Long?,
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
 * Run one capture cycle: page through all available Kubeshark entries,
 * transform, and send to collector in batches.
 *
 * batchSize controls the number of entries per HTTP call to Kubeshark,
 * not a cap on entries per cycle. We page until Kubeshark returns fewer
 * than batchSize entries, meaning we've caught up.
 *
 * nowMs is injectable for testing lag detection. Defaults to wall clock.
 */
suspend fun captureTraffic(
    cursor: Long?,
    batchSize: Int,
    kubesharkClient: KubesharkClient,
    collectorClient: CollectorClient,
    transformer: TrafficTransformer,
    nowMs: Long = System.currentTimeMillis(),
): CaptureResult {
    var currentCursor = cursor
    var pagesProcessed = 0
    var entriesProcessed = 0

    while (true) {
        val entries =
            kubesharkClient.listHttpCalls(
                startMs = currentCursor,
                limit = batchSize,
            )

        if (entries.isEmpty()) break

        pagesProcessed++
        entriesProcessed += entries.size

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

        currentCursor = entries.maxOf { it.ts } + 1

        if (entries.size < batchSize) break
    }

    val lagMs = if (currentCursor != null) nowMs - currentCursor else null
    if (lagMs != null && lagMs > LAG_WARN_THRESHOLD_MS) {
        // TODO: Surface lag to the platform (e.g. via config poll or heartbeat)
        //  so the customer can take action (scale agents, increase sampling, tune batch size)
        logger.warn(
            "Traffic capture lagging: {}ms behind real-time ({} pages, {} entries this cycle)",
            lagMs,
            pagesProcessed,
            entriesProcessed,
        )
    }

    return CaptureResult(
        cursor = currentCursor,
        pagesProcessed = pagesProcessed,
        entriesProcessed = entriesProcessed,
        lagMs = lagMs,
    )
}

/** Warn when cursor is more than 15s behind wall clock */
private const val LAG_WARN_THRESHOLD_MS = 15_000L
