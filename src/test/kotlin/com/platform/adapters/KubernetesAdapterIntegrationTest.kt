package com.platform.adapters

import com.platform.database.DatabaseTestBase
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
 * ## Test Strategy
 * These tests are workflow-focused rather than property-focused. Each test validates
 * a complete user journey rather than individual metadata fields. Fine-grained metadata
 * extraction should be covered by unit tests with mock Kubernetes objects.
 *
 * ## Cluster Setup
 * The k3s cluster has actual running workloads:
 * - **infrastructure namespace**: PostgreSQL, Redis (2 services)
 * - **production namespace**: API Gateway (1 service)
 * - **Note**: Traffic Generator has no Service resource (it's a client, not a server)
 *
 * Total discoverable services: 3
 */
class KubernetesAdapterIntegrationTest : DatabaseTestBase() {
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
                // Discover services from real Kubernetes cluster
                val discoveredServices = adapter.discoverServices(testOrg.id)

                // We should discover exactly 3 services (api-gateway, redis, postgresql)
                // Note: traffic-generator has no Service resource, so it's not discoverable
                assertEquals(3, discoveredServices.size, "Expected exactly 3 services")

                // All services should have required fields set
                assertTrue(discoveredServices.all { it.provider == Provider.KUBERNETES })
                assertTrue(discoveredServices.all { it.discoveredAt != null })
                assertTrue(discoveredServices.all { it.lastSeenAt != null })
                assertTrue(discoveredServices.all { it.cluster == "k3s-test-cluster" })

                // Persist all discovered services
                discoveredServices.forEach { service ->
                    ServiceRepository.create(service)
                }

                // Verify services were persisted with unique IDs
                val page = ServiceRepository.find(organizationId = testOrg.id, limit = 100)
                assertEquals(3, page.items.size)
                assertEquals(
                    page.items.size,
                    page.items
                        .map { it.id }
                        .toSet()
                        .size,
                )

                // Verify api-gateway has rich metadata (labels, annotations, ports, selectors)
                val apiGateway = page.items.find { it.name == "api-gateway" }
                assertNotNull(apiGateway)
                assertEquals("production", apiGateway.namespace)
                assertNotNull(apiGateway.metadata)

                // Check comprehensive metadata extraction
                val metadata = apiGateway.metadata!!
                assertEquals("NodePort", metadata["k8s.service.type"])
                assertEquals("api-gateway", metadata["app"])
                assertEquals("api-gateway", metadata["app.name"])
                assertEquals("1.5.0", metadata["version"])
                assertEquals("gateway", metadata["component"])
                assertEquals("backend", metadata["team"])
                assertEquals("API Gateway service", metadata["description"])
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
    fun `should discover services from both namespaces`() =
        runBlocking {
            val client = KubernetesWorkloadTestBase.createKubernetesClient()
            val adapter = KubernetesAdapter(client = client, clusterName = "k3s-test-cluster")

            try {
                val discoveredServices = adapter.discoverServices(testOrg.id)
                discoveredServices.forEach { service -> ServiceRepository.create(service) }

                // Verify infrastructure namespace has PostgreSQL and Redis
                val infraServices =
                    ServiceRepository.find(
                        organizationId = testOrg.id,
                        namespace = "infrastructure",
                        limit = 100,
                    )
                assertEquals(2, infraServices.items.size)
                assertTrue(infraServices.items.any { it.name == "redis" })
                assertTrue(infraServices.items.any { it.name == "postgresql" })

                // Verify production namespace has API Gateway
                val prodServices =
                    ServiceRepository.find(
                        organizationId = testOrg.id,
                        namespace = "production",
                        limit = 100,
                    )
                assertEquals(1, prodServices.items.size)
                assertTrue(prodServices.items.any { it.name == "api-gateway" })
            } finally {
                adapter.close()
            }
        }

    @Test
    fun `should filter discovery by configured namespaces`() =
        runBlocking {
            val client = KubernetesWorkloadTestBase.createKubernetesClient()

            // Adapter configured to only discover from production namespace
            val adapter =
                KubernetesAdapter(
                    client = client,
                    clusterName = "k3s-test-cluster",
                    namespaces = listOf("production"),
                )

            try {
                val discoveredServices = adapter.discoverServices(testOrg.id)

                // Should only find services from production namespace
                assertEquals(1, discoveredServices.size)
                assertTrue(discoveredServices.all { it.namespace == "production" })
                assertTrue(discoveredServices.any { it.name == "api-gateway" })
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

                // Filter by cluster name
                val clusterServices =
                    ServiceRepository.find(
                        organizationId = testOrg.id,
                        cluster = "k3s-test-cluster",
                        limit = 100,
                    )

                assertEquals(3, clusterServices.items.size)
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
                // First discovery and persist
                val firstDiscovery = adapter.discoverServices(testOrg.id)
                firstDiscovery.forEach { service -> ServiceRepository.create(service) }

                val initialCount = ServiceRepository.find(organizationId = testOrg.id, limit = 100).items.size
                assertEquals(3, initialCount)

                // Second discovery (simulating periodic re-discovery)
                val secondDiscovery = adapter.discoverServices(testOrg.id)

                // Upsert should update existing services, not create duplicates
                secondDiscovery.forEach { service -> ServiceRepository.upsert(service) }

                // Should still have the same number of services
                val finalPage = ServiceRepository.find(organizationId = testOrg.id, limit = 100)
                assertEquals(initialCount, finalPage.items.size)
            } finally {
                adapter.close()
            }
        }
}
