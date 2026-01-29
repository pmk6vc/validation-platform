package com.platform.kubernetes

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
 * Build the API Gateway image before running:
 * ```
 * ./gradlew :test-services:api-gateway:jibDockerBuild
 * ```
 *
 * ## Test Strategy
 * We use kubectl port-forward (via k3s exec) to access the API Gateway from the test,
 * since the k3s cluster runs in a Docker container with its own network.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkloadTrafficIntegrationTest : KubernetesWorkloadTestBase() {
    private val logger = LoggerFactory.getLogger(WorkloadTrafficIntegrationTest::class.java)

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
            val client = createKubernetesClient()
            try {
                // Use kubectl exec to make a request from inside the cluster
                val result =
                    getK3sContainer().execInContainer(
                        "kubectl",
                        "exec",
                        "-n",
                        "production",
                        "deploy/traffic-generator",
                        "--",
                        "curl",
                        "-s",
                        "http://api-gateway.production.svc.cluster.local:8080/api/health",
                    )

                logger.info("Health check response: ${result.stdout}")
                assertEquals(0, result.exitCode, "curl command failed: ${result.stderr}")

                val health = Json.decodeFromString<HealthResponse>(result.stdout)
                assertEquals("healthy", health.status)
                assertEquals("connected", health.database)
                assertEquals("connected", health.cache)
            } finally {
                client.close()
            }
        }

    @Test
    fun `api gateway can query users from postgresql`() =
        runBlocking {
            val result =
                getK3sContainer().execInContainer(
                    "kubectl",
                    "exec",
                    "-n",
                    "production",
                    "deploy/traffic-generator",
                    "--",
                    "curl",
                    "-s",
                    "http://api-gateway.production.svc.cluster.local:8080/api/users",
                )

            logger.info("Users response: ${result.stdout}")
            assertEquals(0, result.exitCode, "curl command failed: ${result.stderr}")

            val users = Json.decodeFromString<List<User>>(result.stdout)
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
            // First request - cache miss
            val result1 =
                getK3sContainer().execInContainer(
                    "kubectl",
                    "exec",
                    "-n",
                    "production",
                    "deploy/traffic-generator",
                    "--",
                    "curl",
                    "-s",
                    "-i",
                    "http://api-gateway.production.svc.cluster.local:8080/api/users",
                )

            logger.info("First request headers: ${result1.stdout.lines().take(10).joinToString("\n")}")
            assertEquals(0, result1.exitCode)

            // Give Redis time to cache
            delay(100)

            // Second request - should be cache hit
            val result2 =
                getK3sContainer().execInContainer(
                    "kubectl",
                    "exec",
                    "-n",
                    "production",
                    "deploy/traffic-generator",
                    "--",
                    "curl",
                    "-s",
                    "-i",
                    "http://api-gateway.production.svc.cluster.local:8080/api/users",
                )

            logger.info("Second request headers: ${result2.stdout.lines().take(10).joinToString("\n")}")
            assertEquals(0, result2.exitCode)

            // Check for X-Cache header (HIT on second request)
            // Note: First might be MISS or HIT depending on traffic generator timing
            val hasXCacheHeader = result2.stdout.contains("X-Cache:")
            assertTrue(hasXCacheHeader, "Expected X-Cache header in response")
        }

    @Test
    fun `api gateway can query individual user by id`() =
        runBlocking {
            val result =
                getK3sContainer().execInContainer(
                    "kubectl",
                    "exec",
                    "-n",
                    "production",
                    "deploy/traffic-generator",
                    "--",
                    "curl",
                    "-s",
                    "http://api-gateway.production.svc.cluster.local:8080/api/users/1",
                )

            logger.info("User 1 response: ${result.stdout}")
            assertEquals(0, result.exitCode, "curl command failed: ${result.stderr}")

            val user = Json.decodeFromString<User>(result.stdout)
            assertEquals(1, user.id)
            assertTrue(user.name.isNotEmpty())
            assertTrue(user.email.isNotEmpty())
        }

    @Test
    fun `api gateway can query orders with user join`() =
        runBlocking {
            val result =
                getK3sContainer().execInContainer(
                    "kubectl",
                    "exec",
                    "-n",
                    "production",
                    "deploy/traffic-generator",
                    "--",
                    "curl",
                    "-s",
                    "http://api-gateway.production.svc.cluster.local:8080/api/orders",
                )

            logger.info("Orders response: ${result.stdout}")
            assertEquals(0, result.exitCode, "curl command failed: ${result.stderr}")

            val orders = Json.decodeFromString<List<Order>>(result.stdout)
            assertTrue(orders.isNotEmpty(), "Expected at least one order")
            assertTrue(orders.size >= 7, "Expected at least 7 seeded orders")

            // Verify join worked - userName should be populated
            val orderWithUser = orders.first()
            assertNotNull(orderWithUser.userName, "Expected userName from JOIN to be populated")
        }

    @Test
    fun `api gateway can query orders by user id`() =
        runBlocking {
            val result =
                getK3sContainer().execInContainer(
                    "kubectl",
                    "exec",
                    "-n",
                    "production",
                    "deploy/traffic-generator",
                    "--",
                    "curl",
                    "-s",
                    "http://api-gateway.production.svc.cluster.local:8080/api/orders/1",
                )

            logger.info("Orders for user 1: ${result.stdout}")
            assertEquals(0, result.exitCode, "curl command failed: ${result.stderr}")

            val orders = Json.decodeFromString<List<Order>>(result.stdout)
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
                    "--tail=20",
                )

            logger.info("Traffic generator logs:\n${logsResult.stdout}")
            assertEquals(0, logsResult.exitCode)

            // Logs should show curl activity or our echo messages
            val hasTrafficIndicator =
                logsResult.stdout.contains("Starting traffic") ||
                    logsResult.stdout.contains("healthy") ||
                    logsResult.stdout.contains("{")

            assertTrue(hasTrafficIndicator, "Expected to see traffic activity in logs")
        }

    @Test
    fun `all workload pods are running`() =
        runBlocking {
            val pods =
                listOf(
                    "infrastructure" to "postgresql",
                    "infrastructure" to "redis",
                    "production" to "api-gateway",
                    "production" to "traffic-generator",
                )

            for ((namespace, app) in pods) {
                val result =
                    getK3sContainer().execInContainer(
                        "kubectl",
                        "get",
                        "pods",
                        "-n",
                        namespace,
                        "-l",
                        "app=$app",
                        "-o",
                        "jsonpath={.items[0].status.phase}",
                    )

                assertEquals(0, result.exitCode, "Failed to get pod status for $namespace/$app")
                assertEquals(
                    "Running",
                    result.stdout.trim(),
                    "Pod $namespace/$app should be running but is: ${result.stdout}",
                )
            }
        }

    @Test
    fun `postgresql has seeded data`() =
        runBlocking {
            // Query PostgreSQL directly to verify data
            val result =
                getK3sContainer().execInContainer(
                    "kubectl",
                    "exec",
                    "-n",
                    "infrastructure",
                    "deploy/postgresql",
                    "--",
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "testdb",
                    "-c",
                    "SELECT COUNT(*) FROM users;",
                )

            logger.info("PostgreSQL user count: ${result.stdout}")
            assertEquals(0, result.exitCode, "psql command failed: ${result.stderr}")
            assertTrue(result.stdout.contains("5"), "Expected 5 users in database")
        }

    @Test
    fun `redis is accepting connections`() =
        runBlocking {
            val result =
                getK3sContainer().execInContainer(
                    "kubectl",
                    "exec",
                    "-n",
                    "infrastructure",
                    "deploy/redis",
                    "--",
                    "redis-cli",
                    "PING",
                )

            logger.info("Redis ping: ${result.stdout}")
            assertEquals(0, result.exitCode, "redis-cli command failed: ${result.stderr}")
            assertTrue(result.stdout.trim() == "PONG", "Expected PONG from Redis")
        }
}
