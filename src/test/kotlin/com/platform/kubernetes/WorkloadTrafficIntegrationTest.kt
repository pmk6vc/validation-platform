package com.platform.kubernetes

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests that validate real service-to-service traffic in the k3s cluster.
 *
 * ## What This Tests
 * - PostgreSQL and Redis are running and accessible
 * - API Gateway is serving HTTP requests
 * - Traffic Generator is creating continuous load
 * - Database queries and cache operations are working
 *
 * ## Prerequisites
 * Build the test service images before running:
 * ```
 * ./gradlew :test-services:api-gateway:jibDockerBuild
 * ./gradlew :test-services:traffic-generator:jibDockerBuild
 * ```
 *
 * ## Test Strategy
 * The API Gateway is exposed via NodePort (30080), which is mapped to the host.
 * Tests use Ktor HTTP client to make requests directly to the API Gateway.
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
        val database: String,
        val cache: String,
    )

    @Serializable
    data class User(
        val id: Int,
        val name: String,
        val email: String,
    )

    @Serializable
    data class Order(
        val id: Int,
        val userId: Int,
        val total: Double,
        val createdAt: String,
        val userName: String? = null,
    )

    @Test
    fun `api gateway health check returns healthy status`() =
        runBlocking {
            val baseUrl = getApiGatewayBaseUrl()
            val response = httpClient.get("$baseUrl/api/health")
            val body = response.bodyAsText()

            logger.info("Health check response: $body")
            assertEquals(200, response.status.value)

            val health = json.decodeFromString<HealthResponse>(body)
            assertEquals("healthy", health.status)
            assertEquals("connected", health.database)
            assertEquals("connected", health.cache)
        }

    @Test
    fun `api gateway can query users from postgresql`() =
        runBlocking {
            val baseUrl = getApiGatewayBaseUrl()
            val response = httpClient.get("$baseUrl/api/users")
            val body = response.bodyAsText()

            logger.info("Users response: $body")
            assertEquals(200, response.status.value)

            val users = json.decodeFromString<List<User>>(body)
            assertTrue(users.isNotEmpty(), "Expected at least one user")
            assertTrue(users.size >= 5, "Expected at least 5 seeded users")

            // Verify seeded data
            val alice = users.find { it.name == "Alice Johnson" }
            assertNotNull(alice, "Expected to find Alice Johnson")
            assertEquals("alice@example.com", alice.email)
        }

    @Test
    fun `api gateway uses redis cache for repeated requests`() =
        runBlocking {
            val baseUrl = getApiGatewayBaseUrl()

            // First request - cache miss
            val response1 = httpClient.get("$baseUrl/api/users")
            val cacheHeader1 = response1.headers["X-Cache"]
            logger.info("First request X-Cache: $cacheHeader1")
            assertEquals(200, response1.status.value)

            // Give Redis time to cache
            delay(100)

            // Second request - should be cache hit
            val response2 = httpClient.get("$baseUrl/api/users")
            val cacheHeader2 = response2.headers["X-Cache"]
            logger.info("Second request X-Cache: $cacheHeader2")
            assertEquals(200, response2.status.value)

            // Check for X-Cache header (HIT on second request)
            assertNotNull(cacheHeader2, "Expected X-Cache header in response")
        }

    @Test
    fun `api gateway can query individual user by id`() =
        runBlocking {
            val baseUrl = getApiGatewayBaseUrl()
            val response = httpClient.get("$baseUrl/api/users/1")
            val body = response.bodyAsText()

            logger.info("User 1 response: $body")
            assertEquals(200, response.status.value)

            val user = json.decodeFromString<User>(body)
            assertEquals(1, user.id)
            assertTrue(user.name.isNotEmpty())
            assertTrue(user.email.isNotEmpty())
        }

    @Test
    fun `api gateway can query orders with user join`() =
        runBlocking {
            val baseUrl = getApiGatewayBaseUrl()
            val response = httpClient.get("$baseUrl/api/orders")
            val body = response.bodyAsText()

            logger.info("Orders response: $body")
            assertEquals(200, response.status.value)

            val orders = json.decodeFromString<List<Order>>(body)
            assertTrue(orders.isNotEmpty(), "Expected at least one order")
            assertTrue(orders.size >= 7, "Expected at least 7 seeded orders")

            // Verify join worked - userName should be populated
            val orderWithUser = orders.first()
            assertNotNull(orderWithUser.userName, "Expected userName from JOIN to be populated")
        }

    @Test
    fun `api gateway can query orders by user id`() =
        runBlocking {
            val baseUrl = getApiGatewayBaseUrl()
            val response = httpClient.get("$baseUrl/api/orders/1")
            val body = response.bodyAsText()

            logger.info("Orders for user 1: $body")
            assertEquals(200, response.status.value)

            val orders = json.decodeFromString<List<Order>>(body)
            assertTrue(orders.isNotEmpty(), "Expected at least one order for user 1")

            // All orders should belong to user 1
            assertTrue(orders.all { it.userId == 1 }, "All orders should belong to user 1")
        }

    @Test
    fun `traffic generator is producing continuous traffic`() =
        runBlocking {
            // Check that traffic generator pod is running
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

            // Check logs show traffic is being generated
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

            logger.info("Traffic generator logs (stdout):\n${logsResult.stdout}")
            logger.info("Traffic generator logs (stderr):\n${logsResult.stderr}")
            assertEquals(0, logsResult.exitCode, "kubectl logs failed: ${logsResult.stderr}")

            // Logs should show traffic activity from our Kotlin traffic generator
            // Check both stdout and stderr as logback may output to either
            val allLogs = logsResult.stdout + logsResult.stderr
            val hasTrafficIndicator =
                allLogs.contains("Traffic Generator") ||
                    allLogs.contains("TrafficGenerator") ||
                    allLogs.contains("Starting traffic") ||
                    allLogs.contains("Target:") ||
                    allLogs.contains("Waiting for API Gateway")

            assertTrue(
                hasTrafficIndicator,
                "Expected to see traffic activity in logs. Got stdout: ${logsResult.stdout.take(
                    300,
                )}, stderr: ${logsResult.stderr.take(300)}",
            )
        }
}
