package com.platform.testservices

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Traffic generator that continuously sends requests to the API Gateway.
 *
 * This creates realistic traffic patterns for Pixie to capture, including:
 * - Health checks
 * - User list queries (triggers PostgreSQL + Redis cache)
 * - Individual user lookups
 * - Order queries with database joins
 */

// Request counters for periodic summary
private val healthRequests = AtomicLong(0)
private val userRequests = AtomicLong(0)
private val orderRequests = AtomicLong(0)
private val errorCount = AtomicLong(0)
private val lastSummaryTime = AtomicLong(System.currentTimeMillis())

private const val SUMMARY_INTERVAL_MS = 30_000L

fun main() = runBlocking {
    val logger = LoggerFactory.getLogger("TrafficGenerator")

    val apiGatewayHost = System.getenv("API_GATEWAY_HOST") ?: "api-gateway.production.svc.cluster.local"
    val apiGatewayPort = System.getenv("API_GATEWAY_PORT")?.toIntOrNull() ?: 8080
    val delayBetweenRoundsMs = System.getenv("DELAY_BETWEEN_ROUNDS_MS")?.toLongOrNull() ?: 2000L
    val initialDelayMs = System.getenv("INITIAL_DELAY_MS")?.toLongOrNull() ?: 30000L

    val baseUrl = "http://$apiGatewayHost:$apiGatewayPort"

    logger.info("Traffic Generator starting")
    logger.info("Target: $baseUrl")
    logger.info("Initial delay: ${initialDelayMs}ms")
    logger.info("Delay between rounds: ${delayBetweenRoundsMs}ms")

    // Wait for API Gateway to be ready
    logger.info("Waiting for API Gateway to be ready...")
    delay(initialDelayMs)

    val client = HttpClient(CIO) {
        engine {
            requestTimeout = 10000
        }
    }

    logger.info("Starting traffic generation...")

    var round = 0L
    while (true) {
        round++
        try {
            // Health check
            makeRequest(client, logger, "$baseUrl/api/health", "health")
            healthRequests.incrementAndGet()

            // List all users (cache miss then hit pattern)
            makeRequest(client, logger, "$baseUrl/api/users", "users")
            userRequests.incrementAndGet()

            // Get individual users
            for (userId in 1..3) {
                makeRequest(client, logger, "$baseUrl/api/users/$userId", "user-$userId")
                userRequests.incrementAndGet()
            }

            // List all orders (database join)
            makeRequest(client, logger, "$baseUrl/api/orders", "orders")
            orderRequests.incrementAndGet()

            // Get orders by user
            for (userId in 1..2) {
                makeRequest(client, logger, "$baseUrl/api/orders/$userId", "orders-user-$userId")
                orderRequests.incrementAndGet()
            }
        } catch (e: Exception) {
            logger.warn("Error in traffic round $round: ${e.message}")
            errorCount.incrementAndGet()
        }

        // Log summary periodically
        logSummaryIfNeeded(logger)

        delay(delayBetweenRoundsMs)
    }
}

private fun logSummaryIfNeeded(logger: org.slf4j.Logger) {
    val now = System.currentTimeMillis()
    val lastTime = lastSummaryTime.get()
    if (now - lastTime >= SUMMARY_INTERVAL_MS) {
        if (lastSummaryTime.compareAndSet(lastTime, now)) {
            val health = healthRequests.getAndSet(0)
            val users = userRequests.getAndSet(0)
            val orders = orderRequests.getAndSet(0)
            val errors = errorCount.getAndSet(0)
            val total = health + users + orders

            logger.info(
                "Traffic summary (last 30s): {} requests sent (health={}, users={}, orders={}), errors={}",
                total,
                health,
                users,
                orders,
                errors
            )
        }
    }
}

private suspend fun makeRequest(
    client: HttpClient,
    logger: org.slf4j.Logger,
    url: String,
    label: String,
) {
    try {
        val response = client.get(url)
        val status = response.status.value
        if (status >= 400) {
            logger.warn("[$label] $url -> $status")
        }
    } catch (e: Exception) {
        logger.warn("[$label] $url -> ERROR: ${e.message}")
    }
}