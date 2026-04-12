package com.platform.testservices

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import java.net.URI
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.util.Timer
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.fixedRateTimer

/**
 * API Gateway - routes requests to order-service with Redis caching.
 *
 * Redis is configured with maxmemory + allkeys-lru eviction, so we get a
 * realistic mix of cache hits and misses without manual TTL management.
 *
 * Routes:
 * - GET /api/health - Health check
 * - GET /api/orders - List orders (cached, proxied to order-service)
 * - GET /api/orders/{id} - Get order (cached, proxied to order-service)
 * - POST /api/orders - Create order (proxied to order-service, invalidates list cache)
 */

private val logger = LoggerFactory.getLogger("ApiGateway")

private val totalRequests = AtomicLong(0)
private val cacheHits = AtomicLong(0)
private val cacheMisses = AtomicLong(0)
private val errorCount = AtomicLong(0)

private const val SUMMARY_INTERVAL_MS = 30_000L

private val orderServiceHost = System.getenv("ORDER_SERVICE_HOST") ?: "localhost"
private val orderServicePort = System.getenv("ORDER_SERVICE_PORT") ?: "8080"
private val redisHost = System.getenv("REDIS_HOST") ?: "localhost"
private val redisPort = (System.getenv("REDIS_PORT") ?: "6379").toInt()

private val orderServiceUrl = "http://$orderServiceHost:$orderServicePort"

private lateinit var jedisPool: JedisPool
private lateinit var httpClient: HttpClient

@Serializable
data class HealthResponse(val status: String, val timestamp: Long, val orderService: String, val cache: String)

@Serializable
data class ErrorResponse(val error: String, val code: Int)

fun main() {
    logger.info("Starting API Gateway...")
    logger.info("Order Service: $orderServiceUrl")
    logger.info("Redis: $redisHost:$redisPort")

    val poolConfig = JedisPoolConfig().apply {
        maxTotal = 10
        maxIdle = 5
        minIdle = 1
        testOnBorrow = true
    }
    jedisPool = JedisPool(poolConfig, redisHost, redisPort)

    httpClient = HttpClient(CIO) {
        engine { requestTimeout = 10000 }
    }

    waitForDependencies()
    startSummaryLogger()

    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            json(Json { prettyPrint = true })
        }
        routing {
            healthRoutes()
            orderRoutes()
        }
    }.start(wait = true)
}

private fun startSummaryLogger(): Timer {
    return fixedRateTimer("summary-logger", daemon = true, initialDelay = SUMMARY_INTERVAL_MS, period = SUMMARY_INTERVAL_MS) {
        val total = totalRequests.getAndSet(0)
        val hits = cacheHits.getAndSet(0)
        val misses = cacheMisses.getAndSet(0)
        val errors = errorCount.getAndSet(0)

        if (total > 0) {
            logger.info(
                "Request summary (last 30s): {} served, cache hits={}, misses={}, errors={}",
                total, hits, misses, errors
            )
        }
    }
}

private fun waitForDependencies() {
    val maxRetries = 30

    var retries = 0
    while (retries < maxRetries) {
        try {
            jedisPool.resource.use { jedis -> jedis.ping() }
            logger.info("Redis is ready")
            break
        } catch (e: Exception) {
            retries++
            logger.warn("Waiting for Redis... (attempt $retries/$maxRetries)")
            Thread.sleep(1000)
        }
    }

    retries = 0
    while (retries < maxRetries) {
        try {
            URI("$orderServiceUrl/api/health").toURL().readText()
            logger.info("Order Service is ready")
            break
        } catch (e: Exception) {
            retries++
            logger.warn("Waiting for Order Service... (attempt $retries/$maxRetries)")
            Thread.sleep(1000)
        }
    }
}

private fun Routing.healthRoutes() {
    get("/api/health") {
        totalRequests.incrementAndGet()
        val orderStatus = withContext(Dispatchers.IO) {
            try {
                httpClient.get("$orderServiceUrl/api/health")
                "connected"
            } catch (e: Exception) {
                "error: ${e.message}"
            }
        }

        val cacheStatus = withContext(Dispatchers.IO) {
            try {
                jedisPool.resource.use { it.ping() }
                "connected"
            } catch (e: Exception) {
                "error: ${e.message}"
            }
        }

        call.respond(HealthResponse(
            status = if (orderStatus == "connected" && cacheStatus == "connected") "healthy" else "degraded",
            timestamp = System.currentTimeMillis(),
            orderService = orderStatus,
            cache = cacheStatus
        ))
    }
}

private fun Routing.orderRoutes() {
    get("/api/orders") {
        totalRequests.incrementAndGet()
        val cacheKey = "orders:all"

        val cached = withContext(Dispatchers.IO) {
            try { jedisPool.resource.use { it.get(cacheKey) } }
            catch (e: Exception) { null }
        }

        if (cached != null) {
            cacheHits.incrementAndGet()
            call.response.headers.append("X-Cache", "HIT")
            call.respondText(cached, ContentType.Application.Json)
            return@get
        }

        cacheMisses.incrementAndGet()
        call.response.headers.append("X-Cache", "MISS")

        val response = withContext(Dispatchers.IO) {
            try {
                httpClient.get("$orderServiceUrl/api/orders").bodyAsText()
            } catch (e: Exception) {
                errorCount.incrementAndGet()
                null
            }
        }

        if (response == null) {
            call.respond(HttpStatusCode.BadGateway, ErrorResponse("Order service unavailable", 502))
            return@get
        }

        withContext(Dispatchers.IO) {
            try { jedisPool.resource.use { it.set(cacheKey, response) } }
            catch (e: Exception) { logger.warn("Cache write failed: ${e.message}") }
        }

        call.respondText(response, ContentType.Application.Json)
    }

    get("/api/orders/{id}") {
        totalRequests.incrementAndGet()
        val orderId = call.parameters["id"]
        if (orderId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing order ID", 400))
            return@get
        }

        val cacheKey = "orders:$orderId"
        val cached = withContext(Dispatchers.IO) {
            try { jedisPool.resource.use { it.get(cacheKey) } }
            catch (e: Exception) { null }
        }

        if (cached != null) {
            cacheHits.incrementAndGet()
            call.response.headers.append("X-Cache", "HIT")
            call.respondText(cached, ContentType.Application.Json)
            return@get
        }

        cacheMisses.incrementAndGet()
        call.response.headers.append("X-Cache", "MISS")

        val response = withContext(Dispatchers.IO) {
            try {
                val resp = httpClient.get("$orderServiceUrl/api/orders/$orderId")
                if (resp.status == HttpStatusCode.NotFound) return@withContext null
                resp.bodyAsText()
            } catch (e: Exception) {
                errorCount.incrementAndGet()
                null
            }
        }

        if (response == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Order not found", 404))
            return@get
        }

        withContext(Dispatchers.IO) {
            try { jedisPool.resource.use { it.set(cacheKey, response) } }
            catch (e: Exception) { logger.warn("Cache write failed: ${e.message}") }
        }

        call.respondText(response, ContentType.Application.Json)
    }

    post("/api/orders") {
        totalRequests.incrementAndGet()
        val body = call.receiveText()

        val response = withContext(Dispatchers.IO) {
            try {
                val resp = httpClient.post("$orderServiceUrl/api/orders") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                resp.status to resp.bodyAsText()
            } catch (e: Exception) {
                errorCount.incrementAndGet()
                null
            }
        }

        if (response == null) {
            call.respond(HttpStatusCode.BadGateway, ErrorResponse("Order service unavailable", 502))
            return@post
        }

        // Invalidate orders list cache since a new order was created
        withContext(Dispatchers.IO) {
            try { jedisPool.resource.use { it.del("orders:all") } }
            catch (e: Exception) { logger.warn("Cache invalidation failed: ${e.message}") }
        }

        call.respondText(response.second, ContentType.Application.Json, response.first)
    }
}