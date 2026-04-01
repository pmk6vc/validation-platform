package com.platform.testservices

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.util.Properties

/**
 * Order service - owns the orders database and produces events to Kafka.
 *
 * Endpoints:
 * - GET /api/health - Health check
 * - GET /api/orders - List all orders
 * - GET /api/orders/{id} - Get order by ID
 * - POST /api/orders - Create order (produces order-event to Kafka)
 */

private val logger = LoggerFactory.getLogger("OrderService")

private val pgHost = System.getenv("POSTGRES_HOST") ?: "localhost"
private val pgPort = System.getenv("POSTGRES_PORT") ?: "5432"
private val pgDb = System.getenv("POSTGRES_DB") ?: "ordersdb"
private val pgUser = System.getenv("POSTGRES_USER") ?: "postgres"
private val pgPassword = System.getenv("POSTGRES_PASSWORD") ?: "testpass"
private val dbUrl = "jdbc:postgresql://$pgHost:$pgPort/$pgDb"

private val kafkaServers = System.getenv("KAFKA_BOOTSTRAP_SERVERS") ?: "localhost:9092"
private val kafkaTopic = System.getenv("KAFKA_TOPIC") ?: "order-events"

private lateinit var kafkaProducer: KafkaProducer<String, String>

@Serializable
data class Order(val id: Int, val total: Double, val status: String, val createdAt: String)

@Serializable
data class CreateOrderRequest(val total: Double)

@Serializable
data class CreateOrderResponse(val id: Int, val total: Double, val status: String)

@Serializable
data class OrderEvent(val orderId: Int, val total: Double, val status: String, val eventType: String)

@Serializable
data class OrderHealthResponse(val status: String, val database: String, val kafka: String)

@Serializable
data class OrderErrorResponse(val error: String, val code: Int)

fun main() {
    logger.info("Starting Order Service...")
    logger.info("PostgreSQL: $pgHost:$pgPort/$pgDb")
    logger.info("Kafka: $kafkaServers, topic: $kafkaTopic")

    waitForDatabase()
    initKafkaProducer()

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

private fun waitForDatabase() {
    val maxRetries = 30
    var retries = 0
    while (retries < maxRetries) {
        try {
            getConnection().use { conn ->
                conn.createStatement().execute("SELECT 1")
            }
            logger.info("PostgreSQL is ready")
            return
        } catch (e: Exception) {
            retries++
            logger.warn("Waiting for PostgreSQL... (attempt $retries/$maxRetries)")
            Thread.sleep(1000)
        }
    }
    logger.error("PostgreSQL did not become ready after $maxRetries attempts")
}

private fun initKafkaProducer() {
    val props = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.ACKS_CONFIG, "1")
        put(ProducerConfig.RETRIES_CONFIG, 3)
    }
    kafkaProducer = KafkaProducer(props)
    logger.info("Kafka producer initialized")
}

private fun getConnection(): Connection {
    return DriverManager.getConnection(dbUrl, pgUser, pgPassword)
}

private fun Routing.healthRoutes() {
    get("/api/health") {
        val dbStatus = withContext(Dispatchers.IO) {
            try {
                getConnection().use { it.createStatement().execute("SELECT 1") }
                "connected"
            } catch (e: Exception) {
                "error: ${e.message}"
            }
        }

        val kafkaStatus = try {
            kafkaProducer.partitionsFor(kafkaTopic)
            "connected"
        } catch (e: Exception) {
            "error: ${e.message}"
        }

        call.respond(OrderHealthResponse(
            status = if (dbStatus == "connected" && kafkaStatus == "connected") "healthy" else "degraded",
            database = dbStatus,
            kafka = kafkaStatus
        ))
    }
}

private fun Routing.orderRoutes() {
    get("/api/orders") {
        val orders = withContext(Dispatchers.IO) {
            val result = mutableListOf<Order>()
            getConnection().use { conn ->
                conn.createStatement().executeQuery(
                    "SELECT id, total, status, created_at FROM orders ORDER BY created_at DESC"
                ).use { rs ->
                    while (rs.next()) {
                        result.add(Order(
                            id = rs.getInt("id"),
                            total = rs.getDouble("total"),
                            status = rs.getString("status"),
                            createdAt = rs.getTimestamp("created_at").toString()
                        ))
                    }
                }
            }
            result
        }
        call.respond(orders)
    }

    get("/api/orders/{id}") {
        val orderId = call.parameters["id"]?.toIntOrNull()
        if (orderId == null) {
            call.respond(HttpStatusCode.BadRequest, OrderErrorResponse("Invalid order ID", 400))
            return@get
        }

        val order = withContext(Dispatchers.IO) {
            getConnection().use { conn ->
                conn.prepareStatement("SELECT id, total, status, created_at FROM orders WHERE id = ?").use { stmt ->
                    stmt.setInt(1, orderId)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            Order(
                                id = rs.getInt("id"),
                                total = rs.getDouble("total"),
                                status = rs.getString("status"),
                                createdAt = rs.getTimestamp("created_at").toString()
                            )
                        } else null
                    }
                }
            }
        }

        if (order == null) {
            call.respond(HttpStatusCode.NotFound, OrderErrorResponse("Order not found", 404))
        } else {
            call.respond(order)
        }
    }

    post("/api/orders") {
        val request = call.receive<CreateOrderRequest>()

        val order = withContext(Dispatchers.IO) {
            getConnection().use { conn ->
                conn.prepareStatement(
                    "INSERT INTO orders (total, status) VALUES (?, 'pending') RETURNING id",
                    Statement.RETURN_GENERATED_KEYS
                ).use { stmt ->
                    stmt.setDouble(1, request.total)
                    stmt.executeUpdate()
                    stmt.generatedKeys.use { rs ->
                        rs.next()
                        CreateOrderResponse(
                            id = rs.getInt(1),
                            total = request.total,
                            status = "pending"
                        )
                    }
                }
            }
        }

        // Produce event to Kafka
        val event = OrderEvent(
            orderId = order.id,
            total = order.total,
            status = order.status,
            eventType = "order.created"
        )
        val record = ProducerRecord(kafkaTopic, order.id.toString(), Json.encodeToString(event))
        kafkaProducer.send(record) { metadata, exception ->
            if (exception != null) {
                logger.error("Failed to produce order event: ${exception.message}")
            } else {
                logger.info("Produced order event for order ${order.id} to ${metadata.topic()}:${metadata.partition()}")
            }
        }

        call.respond(HttpStatusCode.Created, order)
    }
}