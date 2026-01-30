package com.platform.testservices

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.sql.Connection
import java.sql.DriverManager

/**
 * Test API Gateway service for k3s integration testing.
 *
 * This service provides realistic HTTP endpoints that make actual connections
 * to PostgreSQL and Redis, generating traffic patterns that Pixie can capture.
 *
 * Endpoints:
 * - GET /api/health - Health check
 * - GET /api/users - List users (with Redis caching)
 * - GET /api/users/{id} - Get user by ID
 * - GET /api/orders - List orders with user join
 * - GET /api/orders/{userId} - Get orders for a specific user
 */

private val logger = LoggerFactory.getLogger("ApiGateway")

// Environment configuration
private val pgHost = System.getenv("POSTGRES_HOST") ?: "localhost"
private val pgPort = System.getenv("POSTGRES_PORT") ?: "5432"
private val pgDb = System.getenv("POSTGRES_DB") ?: "testdb"
private val pgUser = System.getenv("POSTGRES_USER") ?: "postgres"
private val pgPassword = System.getenv("POSTGRES_PASSWORD") ?: "testpass"
private val redisHost = System.getenv("REDIS_HOST") ?: "localhost"
private val redisPort = (System.getenv("REDIS_PORT") ?: "6379").toInt()

private val dbUrl = "jdbc:postgresql://$pgHost:$pgPort/$pgDb"

// Connection pools
private lateinit var jedisPool: JedisPool

@Serializable
data class User(val id: Int, val name: String, val email: String)

@Serializable
data class Order(val id: Int, val userId: Int, val total: Double, val createdAt: String, val userName: String? = null)

@Serializable
data class HealthResponse(val status: String, val timestamp: Long, val database: String, val cache: String)

@Serializable
data class ErrorResponse(val error: String, val code: Int)

fun main() {
    logger.info("Starting API Gateway...")
    logger.info("PostgreSQL: $pgHost:$pgPort/$pgDb")
    logger.info("Redis: $redisHost:$redisPort")

    // Initialize Redis connection pool
    val poolConfig = JedisPoolConfig().apply {
        maxTotal = 10
        maxIdle = 5
        minIdle = 1
        testOnBorrow = true
    }
    jedisPool = JedisPool(poolConfig, redisHost, redisPort)

    // Wait for dependencies to be ready
    waitForDependencies()

    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            json(Json { prettyPrint = true })
        }

        routing {
            healthRoutes()
            userRoutes()
            orderRoutes()
        }
    }.start(wait = true)
}

/**
 * Wait for PostgreSQL and Redis to be available before starting.
 */
private fun waitForDependencies() {
    val maxRetries = 30
    var retries = 0

    // Wait for PostgreSQL
    while (retries < maxRetries) {
        try {
            getConnection().use { conn ->
                conn.createStatement().execute("SELECT 1")
            }
            logger.info("PostgreSQL is ready")
            break
        } catch (e: Exception) {
            retries++
            logger.warn("Waiting for PostgreSQL... (attempt $retries/$maxRetries)")
            Thread.sleep(1000)
        }
    }

    // Wait for Redis
    retries = 0
    while (retries < maxRetries) {
        try {
            jedisPool.resource.use { jedis ->
                jedis.ping()
            }
            logger.info("Redis is ready")
            break
        } catch (e: Exception) {
            retries++
            logger.warn("Waiting for Redis... (attempt $retries/$maxRetries)")
            Thread.sleep(1000)
        }
    }
}

private fun getConnection(): Connection {
    return DriverManager.getConnection(dbUrl, pgUser, pgPassword)
}

/**
 * Health check routes
 */
private fun Routing.healthRoutes() {
    get("/api/health") {
        val (dbStatus, cacheStatus) = withContext(Dispatchers.IO) {
            val db = try {
                getConnection().use { it.createStatement().execute("SELECT 1") }
                "connected"
            } catch (e: Exception) {
                "error: ${e.message}"
            }

            val cache = try {
                jedisPool.resource.use { it.ping() }
                "connected"
            } catch (e: Exception) {
                "error: ${e.message}"
            }

            db to cache
        }

        call.respond(
            HealthResponse(
                status = if (dbStatus == "connected" && cacheStatus == "connected") "healthy" else "degraded",
                timestamp = System.currentTimeMillis(),
                database = dbStatus,
                cache = cacheStatus
            )
        )
    }
}

/**
 * User routes with Redis caching
 */
private fun Routing.userRoutes() {
    get("/api/users") {
        // Check Redis cache first
        val cacheKey = "users:all"
        val cached = withContext(Dispatchers.IO) {
            try {
                jedisPool.resource.use { jedis ->
                    jedis.get(cacheKey)
                }
            } catch (e: Exception) {
                logger.warn("Redis cache error: ${e.message}")
                null
            }
        }

        if (cached != null) {
            logger.debug("Cache HIT for $cacheKey")
            call.response.headers.append("X-Cache", "HIT")
            call.respondText(cached, ContentType.Application.Json)
            return@get
        }

        // Cache miss - query PostgreSQL
        logger.debug("Cache MISS for $cacheKey")
        call.response.headers.append("X-Cache", "MISS")

        val users = withContext(Dispatchers.IO) {
            val result = mutableListOf<User>()
            getConnection().use { conn ->
                conn.createStatement().executeQuery("SELECT id, name, email FROM users ORDER BY id").use { rs ->
                    while (rs.next()) {
                        result.add(
                            User(
                                id = rs.getInt("id"),
                                name = rs.getString("name"),
                                email = rs.getString("email")
                            )
                        )
                    }
                }
            }
            result
        }

        // Cache the result
        val json = Json.encodeToString(users)
        withContext(Dispatchers.IO) {
            try {
                jedisPool.resource.use { jedis ->
                    jedis.setex(cacheKey, 60, json)
                }
            } catch (e: Exception) {
                logger.warn("Failed to cache result: ${e.message}")
            }
        }

        call.respond(users)
    }

    get("/api/users/{id}") {
        val userId = call.parameters["id"]?.toIntOrNull()
        if (userId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID", 400))
            return@get
        }

        // Check cache
        val cacheKey = "users:$userId"
        val cached = withContext(Dispatchers.IO) {
            try {
                jedisPool.resource.use { jedis ->
                    jedis.get(cacheKey)
                }
            } catch (e: Exception) {
                logger.warn("Redis cache error: ${e.message}")
                null
            }
        }

        if (cached != null) {
            call.response.headers.append("X-Cache", "HIT")
            call.respondText(cached, ContentType.Application.Json)
            return@get
        }

        call.response.headers.append("X-Cache", "MISS")

        val user = withContext(Dispatchers.IO) {
            getConnection().use { conn ->
                conn.prepareStatement("SELECT id, name, email FROM users WHERE id = ?").use { stmt ->
                    stmt.setInt(1, userId)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            User(
                                id = rs.getInt("id"),
                                name = rs.getString("name"),
                                email = rs.getString("email")
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        }

        if (user == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found", 404))
            return@get
        }

        // Cache the result
        val json = Json.encodeToString(user)
        withContext(Dispatchers.IO) {
            try {
                jedisPool.resource.use { jedis ->
                    jedis.setex(cacheKey, 60, json)
                }
            } catch (e: Exception) {
                logger.warn("Failed to cache result: ${e.message}")
            }
        }

        call.respond(user)
    }
}

/**
 * Order routes with database joins
 */
private fun Routing.orderRoutes() {
    get("/api/orders") {
        val orders = withContext(Dispatchers.IO) {
            val result = mutableListOf<Order>()
            getConnection().use { conn ->
                conn.createStatement().executeQuery(
                    """
                    SELECT o.id, o.user_id, o.total, o.created_at, u.name as user_name
                    FROM orders o
                    JOIN users u ON o.user_id = u.id
                    ORDER BY o.created_at DESC
                    """.trimIndent()
                ).use { rs ->
                    while (rs.next()) {
                        result.add(
                            Order(
                                id = rs.getInt("id"),
                                userId = rs.getInt("user_id"),
                                total = rs.getDouble("total"),
                                createdAt = rs.getTimestamp("created_at").toString(),
                                userName = rs.getString("user_name")
                            )
                        )
                    }
                }
            }
            result
        }
        call.respond(orders)
    }

    get("/api/orders/{userId}") {
        val userId = call.parameters["userId"]?.toIntOrNull()
        if (userId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID", 400))
            return@get
        }

        // Check cache for user's orders
        val cacheKey = "orders:user:$userId"
        val cached = withContext(Dispatchers.IO) {
            try {
                jedisPool.resource.use { jedis ->
                    jedis.get(cacheKey)
                }
            } catch (e: Exception) {
                logger.warn("Redis cache error: ${e.message}")
                null
            }
        }

        if (cached != null) {
            call.response.headers.append("X-Cache", "HIT")
            call.respondText(cached, ContentType.Application.Json)
            return@get
        }

        call.response.headers.append("X-Cache", "MISS")

        val orders = withContext(Dispatchers.IO) {
            val result = mutableListOf<Order>()
            getConnection().use { conn ->
                conn.prepareStatement(
                    """
                    SELECT id, user_id, total, created_at
                    FROM orders
                    WHERE user_id = ?
                    ORDER BY created_at DESC
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setInt(1, userId)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            result.add(
                                Order(
                                    id = rs.getInt("id"),
                                    userId = rs.getInt("user_id"),
                                    total = rs.getDouble("total"),
                                    createdAt = rs.getTimestamp("created_at").toString()
                                )
                            )
                        }
                    }
                }
            }
            result
        }

        // Cache the result
        val json = Json.encodeToString(orders)
        withContext(Dispatchers.IO) {
            try {
                jedisPool.resource.use { jedis ->
                    jedis.setex(cacheKey, 30, json)
                }
            } catch (e: Exception) {
                logger.warn("Failed to cache result: ${e.message}")
            }
        }

        call.respond(orders)
    }
}