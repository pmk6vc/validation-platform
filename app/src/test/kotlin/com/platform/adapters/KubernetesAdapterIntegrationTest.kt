package com.platform.adapters

import com.platform.database.AppDatabaseTestBase
import com.platform.database.OrganizationRepository
import com.platform.database.ServiceRepository
import com.platform.kubernetes.KubernetesWorkloadTestBase
import com.platform.models.Organization
import com.platform.models.Provider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for the Kubernetes adapter workflow with a real k3s cluster.
 *
 * ## Cluster Setup
 * The k3s cluster has actual running workloads:
 * - **infrastructure namespace**: orders-db, redis, kafka (3 services)
 * - **production namespace**: api-gateway, order-service, notification-service (3 services)
 * - **external namespace**: webhook-stub (1 service)
 * - **Note**: Traffic Generator has no Service resource (it's a client, not a server)
 *
 * Total discoverable services: 7
 */
class KubernetesAdapterIntegrationTest : AppDatabaseTestBase() {
    private lateinit var testOrg: Organization

    @BeforeEach
    fun setupOrganization() {
        runBlocking {
            testOrg =
                Organization(
                    id = UUID.randomUUID().toString(),
                    name = "Test Organization",
                    createdAt = Instant.now(),
                )
            OrganizationRepository.create(testOrg)
        }
    }

    @Test
    fun `should discover services and persist with correct provider and metadata`() =
        runBlocking {
            val client = KubernetesWorkloadTestBase.createKubernetesClient()
            val adapter = KubernetesAdapter(client = client, clusterName = "k3s-test-cluster")

            try {
                val discoveredServices = adapter.discoverServices(testOrg.id)

                // 7 services: orders-db, redis, kafka, api-gateway, order-service,
                // notification-service, webhook-stub
                // traffic-generator has no Service resource
                assertEquals(7, discoveredServices.size, "Expected exactly 7 services")

                assertTrue(discoveredServices.all { it.provider == Provider.KUBERNETES })
                assertTrue(discoveredServices.all { it.cluster == "k3s-test-cluster" })

                // Persist all discovered services
                discoveredServices.forEach { service ->
                    ServiceRepository.create(service)
                }

                val page = ServiceRepository.find(organizationId = testOrg.id, limit = 100)
                assertEquals(7, page.items.size)
                assertEquals(
                    page.items.size,
                    page.items
                        .map { it.id }
                        .toSet()
                        .size,
                )

                // Verify api-gateway has rich metadata
                val apiGateway = page.items.find { it.name == "api-gateway" }
                assertNotNull(apiGateway)
                assertEquals("production", apiGateway.namespace)
                assertNotNull(apiGateway.metadata)

                val metadata = apiGateway.metadata!!
                assertEquals("NodePort", metadata["k8s.service.type"])
                assertEquals("api-gateway", metadata["app"])
                assertEquals("api-gateway", metadata["app.name"])
                assertEquals("1.5.0", metadata["version"])
                assertEquals("gateway", metadata["component"])
                assertEquals("backend", metadata["team"])
                assertEquals(
                    "API Gateway - routes to backend services with caching",
                    metadata["description"],
                )
                assertEquals("backend-team@company.com", metadata["owner"])
                assertNotNull(metadata["k8s.uid"])
                assertNotNull(metadata["k8s.created.at"])
                assertTrue(metadata["k8s.ports"]?.contains("http:8080/TCP") == true)
                assertTrue(metadata["k8s.selector"]?.contains("app=api-gateway") == true)
                assertTrue(metadata["k8s.selector"]?.contains("version=v1") == true)
            } finally {
                adapter.close()
            }
        }

    @Test
    fun `should discover services from all namespaces`() =
        runBlocking {
            val client = KubernetesWorkloadTestBase.createKubernetesClient()
            val adapter = KubernetesAdapter(client = client, clusterName = "k3s-test-cluster")

            try {
                val discoveredServices = adapter.discoverServices(testOrg.id)
                discoveredServices.forEach { service -> ServiceRepository.create(service) }

                // infrastructure: orders-db, redis, kafka
                val infraServices =
                    ServiceRepository.find(
                        organizationId = testOrg.id,
                        namespace = "infrastructure",
                        limit = 100,
                    )
                assertEquals(3, infraServices.items.size)
                assertTrue(infraServices.items.any { it.name == "redis" })
                assertTrue(infraServices.items.any { it.name == "orders-db" })
                assertTrue(infraServices.items.any { it.name == "kafka" })

                // production: api-gateway, order-service, notification-service
                val prodServices =
                    ServiceRepository.find(
                        organizationId = testOrg.id,
                        namespace = "production",
                        limit = 100,
                    )
                assertEquals(3, prodServices.items.size)
                assertTrue(prodServices.items.any { it.name == "api-gateway" })
                assertTrue(prodServices.items.any { it.name == "order-service" })
                assertTrue(prodServices.items.any { it.name == "notification-service" })

                // external: webhook-stub
                val externalServices =
                    ServiceRepository.find(
                        organizationId = testOrg.id,
                        namespace = "external",
                        limit = 100,
                    )
                assertEquals(1, externalServices.items.size)
                assertTrue(externalServices.items.any { it.name == "webhook-stub" })
            } finally {
                adapter.close()
            }
        }

    @Test
    fun `should filter discovery by configured namespaces`() =
        runBlocking {
            val client = KubernetesWorkloadTestBase.createKubernetesClient()

            val adapter =
                KubernetesAdapter(
                    client = client,
                    clusterName = "k3s-test-cluster",
                    namespaces = listOf("production"),
                )

            try {
                val discoveredServices = adapter.discoverServices(testOrg.id)

                assertEquals(3, discoveredServices.size)
                assertTrue(discoveredServices.all { it.namespace == "production" })
                assertTrue(discoveredServices.any { it.name == "api-gateway" })
                assertTrue(discoveredServices.any { it.name == "order-service" })
                assertTrue(discoveredServices.any { it.name == "notification-service" })
            } finally {
                adapter.close()
            }
        }

    @Test
    fun `should support filtering persisted services by cluster`() =
        runBlocking {
            val client = KubernetesWorkloadTestBase.createKubernetesClient()
            val adapter = KubernetesAdapter(client = client, clusterName = "k3s-test-cluster")

            try {
                val discoveredServices = adapter.discoverServices(testOrg.id)
                discoveredServices.forEach { service -> ServiceRepository.create(service) }

                val clusterServices =
                    ServiceRepository.find(
                        organizationId = testOrg.id,
                        cluster = "k3s-test-cluster",
                        limit = 100,
                    )

                assertEquals(7, clusterServices.items.size)
                assertTrue(clusterServices.items.all { it.cluster == "k3s-test-cluster" })
            } finally {
                adapter.close()
            }
        }

    @Test
    fun `should handle upsert for re-discovery without creating duplicates`() =
        runBlocking {
            val client = KubernetesWorkloadTestBase.createKubernetesClient()
            val adapter = KubernetesAdapter(client = client, clusterName = "k3s-test-cluster")

            try {
                val firstDiscovery = adapter.discoverServices(testOrg.id)
                firstDiscovery.forEach { service -> ServiceRepository.create(service) }

                val initialCount =
                    ServiceRepository.find(organizationId = testOrg.id, limit = 100).items.size
                assertEquals(7, initialCount)

                val secondDiscovery = adapter.discoverServices(testOrg.id)
                secondDiscovery.forEach { service -> ServiceRepository.upsert(service) }

                val finalPage = ServiceRepository.find(organizationId = testOrg.id, limit = 100)
                assertEquals(initialCount, finalPage.items.size)
            } finally {
                adapter.close()
            }
        }
}
