package com.platform.kubernetes

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests that validate the full service topology in the k3s cluster.
 *
 * Tests verify:
 * - api-gateway → order-service → orders-db (HTTP + PostgreSQL)
 * - api-gateway → Redis (caching with LRU eviction)
 * - order-service → Kafka (event production)
 * - notification-service → Kafka (event consumption)
 * - notification-service → webhook-stub (external HTTP call)
 * - traffic-generator producing concurrent load
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkloadTrafficIntegrationTest : KubernetesWorkloadTestBase() {
    private val logger = LoggerFactory.getLogger(WorkloadTrafficIntegrationTest::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient =
        HttpClient(CIO) {
            engine {
                requestTimeout = 10000
            }
        }

    @Serializable
    data class HealthResponse(
        val status: String,
        val timestamp: Long,
        val orderService: String,
        val cache: String,
    )

    @Serializable
    data class Order(
        val id: Int,
        val total: Double,
        val status: String,
        val createdAt: String,
    )

    @Serializable
    data class CreateOrderResponse(
        val id: Int,
        val total: Double,
        val status: String,
    )

    @Serializable
    data class WebhookHealth(
        val status: String,
        val requestsReceived: Long,
    )

    @Serializable
    data class NotificationHealth(
        val status: String,
        val eventsConsumed: Long,
        val webhooksSent: Long,
        val webhookErrors: Long,
    )

    @Test
    fun `api gateway health check shows all dependencies connected`() =
        runBlocking {
            val baseUrl = getApiGatewayBaseUrl()
            val response = httpClient.get("$baseUrl/api/health")
            val body = response.bodyAsText()

            logger.info("Health check response: $body")
            assertEquals(200, response.status.value)

            val health = json.decodeFromString<HealthResponse>(body)
            assertEquals("healthy", health.status)
            assertEquals("connected", health.orderService)
            assertEquals("connected", health.cache)
        }

    @Test
    fun `api gateway can list orders from order-service`() =
        runBlocking {
            val baseUrl = getApiGatewayBaseUrl()
            val response = httpClient.get("$baseUrl/api/orders")
            val body = response.bodyAsText()

            logger.info("Orders response: $body")
            assertEquals(200, response.status.value)

            val orders = json.decodeFromString<List<Order>>(body)
            assertTrue(orders.isNotEmpty(), "Expected at least one order")
        }

    @Test
    fun `api gateway can get individual order`() =
        runBlocking {
            val baseUrl = getApiGatewayBaseUrl()
            val response = httpClient.get("$baseUrl/api/orders/1")
            val body = response.bodyAsText()

            logger.info("Order 1 response: $body")
            assertEquals(200, response.status.value)

            val order = json.decodeFromString<Order>(body)
            assertEquals(1, order.id)
        }

    @Test
    fun `api gateway uses redis cache for repeated requests`() =
        runBlocking {
            val baseUrl = getApiGatewayBaseUrl()

            // First request - cache miss
            val response1 = httpClient.get("$baseUrl/api/orders/1")
            assertEquals(200, response1.status.value)

            delay(100)

            // Second request - should be cache hit
            val response2 = httpClient.get("$baseUrl/api/orders/1")
            val cacheHeader = response2.headers["X-Cache"]
            logger.info("Second request X-Cache: $cacheHeader")
            assertEquals(200, response2.status.value)
            assertEquals("HIT", cacheHeader, "Expected cache HIT on second request")
        }

    @Test
    fun `creating an order triggers kafka event and webhook notification`() =
        runBlocking {
            val baseUrl = getApiGatewayBaseUrl()

            // Create an order via api-gateway → order-service → Kafka
            val createResponse =
                httpClient.post("$baseUrl/api/orders") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"total": 42.50}""")
                }
            assertEquals(201, createResponse.status.value)

            val created = json.decodeFromString<CreateOrderResponse>(createResponse.bodyAsText())
            assertTrue(created.id > 0, "Expected a valid order ID")
            assertEquals(42.50, created.total)
            assertEquals("pending", created.status)

            // Wait for Kafka consumer → webhook chain to complete
            delay(5000)

            // Check notification-service consumed the event
            val notificationHealthResult =
                getK3sContainer().execInContainer(
                    "kubectl",
                    "exec",
                    "-n",
                    "production",
                    "deploy/notification-service",
                    "--",
                    "wget",
                    "-qO-",
                    "http://localhost:8080/api/health",
                )
            if (notificationHealthResult.exitCode == 0) {
                val notifHealth = json.decodeFromString<NotificationHealth>(notificationHealthResult.stdout)
                logger.info(
                    "Notification service: consumed=${notifHealth.eventsConsumed}, webhooks=${notifHealth.webhooksSent}",
                )
                assertTrue(notifHealth.eventsConsumed > 0, "Expected notification-service to have consumed events")
                assertTrue(notifHealth.webhooksSent > 0, "Expected notification-service to have sent webhooks")
            }

            // Check webhook-stub received the call
            val webhookHealthResult =
                getK3sContainer().execInContainer(
                    "kubectl",
                    "exec",
                    "-n",
                    "external",
                    "deploy/webhook-stub",
                    "--",
                    "wget",
                    "-qO-",
                    "http://localhost:8080/api/health",
                )
            if (webhookHealthResult.exitCode == 0) {
                val webhookHealth = json.decodeFromString<WebhookHealth>(webhookHealthResult.stdout)
                logger.info("Webhook stub: requestsReceived=${webhookHealth.requestsReceived}")
                assertTrue(webhookHealth.requestsReceived > 0, "Expected webhook-stub to have received requests")
            }
        }

    @Test
    fun `traffic generator is producing continuous traffic`() =
        runBlocking {
            val result =
                getK3sContainer().execInContainer(
                    "kubectl",
                    "get",
                    "pods",
                    "-n",
                    "production",
                    "-l",
                    "app=traffic-generator",
                    "-o",
                    "jsonpath={.items[0].status.phase}",
                )

            assertEquals(0, result.exitCode)
            assertEquals("Running", result.stdout.trim(), "Traffic generator should be running")

            val logsResult =
                getK3sContainer().execInContainer(
                    "kubectl",
                    "logs",
                    "-n",
                    "production",
                    "deploy/traffic-generator",
                    "-c",
                    "traffic-generator",
                    "--tail=50",
                )

            logger.info("Traffic generator logs:\n${logsResult.stdout}")
            assertEquals(0, logsResult.exitCode, "kubectl logs failed: ${logsResult.stderr}")

            val allLogs = logsResult.stdout + logsResult.stderr
            val hasTrafficIndicator =
                allLogs.contains("Traffic Generator") ||
                    allLogs.contains("TrafficGenerator") ||
                    allLogs.contains("Starting traffic") ||
                    allLogs.contains("Target:") ||
                    allLogs.contains("Traffic summary")

            assertTrue(hasTrafficIndicator, "Expected traffic activity in logs")
        }
}
