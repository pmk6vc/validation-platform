package com.platform.testservices

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Traffic generator that continuously sends requests to the API Gateway.
 *
 * This creates realistic traffic patterns for Pixie to capture, including:
 * - Health checks
 * - User list queries (triggers PostgreSQL + Redis cache)
 * - Individual user lookups
 * - Order queries with database joins
 */
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

            // List all users (cache miss then hit pattern)
            makeRequest(client, logger, "$baseUrl/api/users", "users")

            // Get individual users
            for (userId in 1..3) {
                makeRequest(client, logger, "$baseUrl/api/users/$userId", "user-$userId")
            }

            // List all orders (database join)
            makeRequest(client, logger, "$baseUrl/api/orders", "orders")

            // Get orders by user
            for (userId in 1..2) {
                makeRequest(client, logger, "$baseUrl/api/orders/$userId", "orders-user-$userId")
            }

            if (round % 10 == 0L) {
                logger.info("Completed $round traffic rounds")
            }
        } catch (e: Exception) {
            logger.warn("Error in traffic round $round: ${e.message}")
        }

        delay(delayBetweenRoundsMs)
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