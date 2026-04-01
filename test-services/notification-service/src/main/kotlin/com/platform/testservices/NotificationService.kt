package com.platform.testservices

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong

/**
 * Notification service - consumes order events from Kafka and sends webhooks.
 *
 * Exercises:
 * - MESSAGE_QUEUE dependency (Kafka consumer)
 * - EXTERNAL dependency (HTTP call to webhook-stub)
 *
 * Endpoints:
 * - GET /api/health - Health check with event processing stats
 */

private val logger = LoggerFactory.getLogger("NotificationService")

private val kafkaServers = System.getenv("KAFKA_BOOTSTRAP_SERVERS") ?: "localhost:9092"
private val kafkaTopic = System.getenv("KAFKA_TOPIC") ?: "order-events"
private val kafkaGroupId = System.getenv("KAFKA_GROUP_ID") ?: "notification-service"
private val webhookUrl = System.getenv("WEBHOOK_URL") ?: "http://localhost:8081/webhook"

private val eventsConsumed = AtomicLong(0)
private val webhooksSent = AtomicLong(0)
private val webhookErrors = AtomicLong(0)

@Serializable
data class NotificationHealthResponse(
    val status: String,
    val eventsConsumed: Long,
    val webhooksSent: Long,
    val webhookErrors: Long,
)

fun main() {
    runBlocking {
        logger.info("Starting Notification Service...")
    logger.info("Kafka: $kafkaServers, topic: $kafkaTopic, group: $kafkaGroupId")
    logger.info("Webhook URL: $webhookUrl")

    val httpClient = HttpClient(CIO) {
        engine { requestTimeout = 5000 }
    }

    // Start Kafka consumer in background
    launch(Dispatchers.IO) {
        consumeEvents(httpClient)
    }

    // Start health endpoint
    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            json(Json { prettyPrint = true })
        }
        routing {
            get("/api/health") {
                call.respond(NotificationHealthResponse(
                    status = "healthy",
                    eventsConsumed = eventsConsumed.get(),
                    webhooksSent = webhooksSent.get(),
                    webhookErrors = webhookErrors.get()
                ))
            }
        }
    }.start(wait = true)
    }
}

private suspend fun consumeEvents(httpClient: HttpClient) {
    val props = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServers)
        put(ConsumerConfig.GROUP_ID_CONFIG, kafkaGroupId)
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
    }

    // Retry connecting to Kafka
    var consumer: KafkaConsumer<String, String>? = null
    for (attempt in 1..30) {
        try {
            consumer = KafkaConsumer(props)
            consumer.subscribe(listOf(kafkaTopic))
            logger.info("Kafka consumer connected and subscribed to $kafkaTopic")
            break
        } catch (e: Exception) {
            logger.warn("Waiting for Kafka... (attempt $attempt/30)")
            Thread.sleep(2000)
        }
    }

    if (consumer == null) {
        logger.error("Could not connect to Kafka after 30 attempts")
        return
    }

    // Poll loop
    while (true) {
        try {
            val records = consumer.poll(Duration.ofSeconds(1))
            for (record in records) {
                eventsConsumed.incrementAndGet()
                logger.info("Consumed event: key=${record.key()}, value=${record.value().take(200)}")

                // Forward to webhook
                try {
                    httpClient.post(webhookUrl) {
                        contentType(ContentType.Application.Json)
                        setBody(record.value())
                    }
                    webhooksSent.incrementAndGet()
                    logger.info("Webhook sent for event key=${record.key()}")
                } catch (e: Exception) {
                    webhookErrors.incrementAndGet()
                    logger.warn("Webhook failed for event key=${record.key()}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            logger.error("Error in consumer poll loop: ${e.message}")
            Thread.sleep(1000)
        }
    }
}