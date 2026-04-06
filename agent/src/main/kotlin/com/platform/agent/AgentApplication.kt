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
            cursor = captureTraffic(cursor, config.batchSize, kubesharkClient, collectorClient, transformer)
        } catch (e: Exception) {
            logger.error("Traffic capture loop failed", e)
        }
        delay(config.captureIntervalMs)
    }
}

// --- Single-iteration logic (testable without loops) ---

/**
 * V1 stub — service discovery will be implemented when the platform
 * exposes a service registration endpoint.
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
 * Run one capture cycle: poll Kubeshark, transform, send to collector.
 * Returns the updated cursor for the next cycle.
 */
suspend fun captureTraffic(
    cursor: Long?,
    batchSize: Int,
    kubesharkClient: KubesharkClient,
    collectorClient: CollectorClient,
    transformer: TrafficTransformer,
): Long? {
    val entries =
        kubesharkClient.listHttpCalls(
            startMs = cursor,
            limit = batchSize,
        )

    if (entries.isEmpty()) return cursor

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

    return entries.maxOf { it.ts } + 1
}
