package com.platform.testservices

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Traffic generator that sends concurrent requests to the API Gateway.
 *
 * Launches multiple coroutines that independently:
 * - GET /api/orders/{random id} (random order lookups, cache hit/miss via LRU)
 * - POST /api/orders (creates orders, triggers Kafka → notification → webhook)
 * - GET /api/health (periodic health checks)
 */

private val logger = LoggerFactory.getLogger("TrafficGenerator")
private val json = Json { ignoreUnknownKeys = true }

private val getRequests = AtomicLong(0)
private val postRequests = AtomicLong(0)
private val errorCount = AtomicLong(0)
private val maxOrderId = AtomicLong(1)

private const val SUMMARY_INTERVAL_MS = 30_000L

@Serializable
data class OrderSummary(val id: Int, val total: Double, val status: String, val createdAt: String)

fun main() {
    runBlocking {
    val apiGatewayHost = System.getenv("API_GATEWAY_HOST") ?: "api-gateway.production.svc.cluster.local"
    val apiGatewayPort = System.getenv("API_GATEWAY_PORT")?.toIntOrNull() ?: 8080
    val initialDelayMs = System.getenv("INITIAL_DELAY_MS")?.toLongOrNull() ?: 30000L
    val concurrency = System.getenv("CONCURRENCY")?.toIntOrNull() ?: 5

    val baseUrl = "http://$apiGatewayHost:$apiGatewayPort"

    logger.info("Traffic Generator starting")
    logger.info("Target: $baseUrl")
    logger.info("Concurrency: $concurrency reader coroutines")
    logger.info("Initial delay: ${initialDelayMs}ms")

    delay(initialDelayMs)

    val client = HttpClient(CIO) {
        engine {
            requestTimeout = 10000
            maxConnectionsCount = 100
        }
    }

    // Fetch initial order count from the API
    initMaxOrderId(client, baseUrl)

    logger.info("Starting traffic generation with maxOrderId=${maxOrderId.get()}...")

    coroutineScope {
        launch { healthLoop(client, baseUrl) }

        repeat(concurrency) {
            launch { readerLoop(client, baseUrl) }
        }

        launch { writerLoop(client, baseUrl) }

        launch { summaryLoop() }
    }
    }
}

private suspend fun initMaxOrderId(client: HttpClient, baseUrl: String) {
    for (attempt in 1..30) {
        try {
            val response = client.get("$baseUrl/api/orders").bodyAsText()
            val orders = json.decodeFromString<List<OrderSummary>>(response)
            if (orders.isNotEmpty()) {
                maxOrderId.set(orders.maxOf { it.id }.toLong())
                logger.info("Initialized maxOrderId=${maxOrderId.get()} from ${orders.size} existing orders")
                return
            }
        } catch (e: Exception) {
            logger.warn("Waiting for API Gateway... (attempt $attempt/30): ${e.message}")
        }
        delay(2000)
    }
    logger.warn("Could not fetch initial orders, starting with maxOrderId=1")
}

private suspend fun healthLoop(client: HttpClient, baseUrl: String) {
    while (true) {
        try {
            client.get("$baseUrl/api/health")
            getRequests.incrementAndGet()
        } catch (e: Exception) {
            errorCount.incrementAndGet()
        }
        delay(5000)
    }
}

private suspend fun readerLoop(client: HttpClient, baseUrl: String) {
    while (true) {
        try {
            val orderId = Random.nextInt(1, maxOrderId.get().toInt() + 1)
            client.get("$baseUrl/api/orders/$orderId")
            getRequests.incrementAndGet()
        } catch (e: Exception) {
            errorCount.incrementAndGet()
        }
        delay(Random.nextLong(200, 800))
    }
}

private suspend fun writerLoop(client: HttpClient, baseUrl: String) {
    while (true) {
        try {
            val total = 10.0 + (Random.nextDouble() * 490.0)
            val response = client.post("$baseUrl/api/orders") {
                contentType(ContentType.Application.Json)
                setBody("""{"total": ${"%.2f".format(total)}}""")
            }
            if (response.status.value < 400) {
                maxOrderId.incrementAndGet()
                postRequests.incrementAndGet()
            } else {
                errorCount.incrementAndGet()
            }
        } catch (e: Exception) {
            errorCount.incrementAndGet()
        }
        delay(Random.nextLong(1000, 3000))
    }
}

private suspend fun summaryLoop() {
    while (true) {
        delay(SUMMARY_INTERVAL_MS)
        val gets = getRequests.getAndSet(0)
        val posts = postRequests.getAndSet(0)
        val errors = errorCount.getAndSet(0)
        logger.info(
            "Traffic summary (last 30s): {} reads, {} writes, {} errors, maxOrderId={}",
            gets, posts, errors, maxOrderId.get()
        )
    }
}