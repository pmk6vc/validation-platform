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

/**
 * Loop 1: Discover K8s services and register them with the platform.
 * V1 stub — service discovery will be implemented when the platform
 * exposes a service registration endpoint.
 */
suspend fun serviceDiscoveryLoop(dynamicConfig: AtomicReference<DynamicConfig>) {
    while (true) {
        try {
            logger.debug("Service discovery loop: not yet implemented")
        } catch (e: Exception) {
            logger.error("Service discovery loop failed", e)
        }
        delay(dynamicConfig.get().discoveryIntervalMs)
    }
}

/**
 * Loop 2: Poll the platform for dynamic config updates.
 * Updates the shared AtomicReference so other loops pick up changes
 * on their next iteration.
 */
suspend fun configPollLoop(
    configClient: ConfigClient,
    dynamicConfig: AtomicReference<DynamicConfig>,
) {
    while (true) {
        try {
            val newConfig = configClient.fetchConfig()
            if (newConfig != null) {
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
            }
        } catch (e: Exception) {
            logger.error("Config poll loop failed", e)
        }
        delay(dynamicConfig.get().configPollIntervalMs)
    }
}

/**
 * Loop 3: Poll Kubeshark for HTTP traffic, transform, and push to collector.
 * Uses a cursor (timestamp) to avoid re-processing entries.
 */
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
            val entries =
                kubesharkClient.listHttpCalls(
                    startMs = cursor,
                    limit = config.batchSize,
                )

            if (entries.isNotEmpty()) {
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

                cursor = entries.maxOf { it.ts } + 1
            }
        } catch (e: Exception) {
            logger.error("Traffic capture loop failed", e)
        }

        delay(config.captureIntervalMs)
    }
}
