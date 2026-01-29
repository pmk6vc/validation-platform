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
 * Integration test demonstrating the full Kubernetes adapter workflow with a real cluster:
 * 1. Spin up a k3s cluster with running workloads (handled by KubernetesWorkloadTestBase)
 * 2. Adapter discovers services from the real cluster
 * 3. Services are persisted to the database via repository
 * 4. Services can be queried back from the database with proper filtering
 *
 * The cluster has actual running workloads: PostgreSQL, Redis, API Gateway, and Traffic Generator.
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
    fun `should discover and persist real Kubernetes services to database`() =
        runBlocking {
            // Create adapter connected to real k3s cluster
            val client = KubernetesWorkloadTestBase.createKubernetesClient()

            val adapter =
                KubernetesAdapter(
                    client = client,
                    clusterName = "k3s-test-cluster",
                )

            try {
                // Discover services from real Kubernetes cluster
                val discoveredServices = adapter.discoverServices(testOrg.id)

                // We should discover our 3 services (api-gateway, redis, postgresql)
                assertTrue(
                    discoveredServices.size >= 3,
                    "Expected at least 3 services, found ${discoveredServices.size}",
                )

                // Persist all discovered services
                discoveredServices.forEach { service ->
                    ServiceRepository.create(service)
                }

                // Verify services were persisted
                val page = ServiceRepository.find(organizationId = testOrg.id, limit = 100)
                assertTrue(page.items.size >= 3)
            } finally {
                adapter.close()
            }
        }

    @Test
    fun `should preserve provider information when persisting`() =
        runBlocking {
            val client = KubernetesWorkloadTestBase.createKubernetesClient()
            val adapter = KubernetesAdapter(client = client, clusterName = "k3s-test-cluster")

            try {
                val discoveredServices = adapter.discoverServices(testOrg.id)

                discoveredServices.forEach { service ->
                    ServiceRepository.create(service)
                }

                val page = ServiceRepository.find(organizationId = testOrg.id, limit = 100)
                assertTrue(page.items.all { it.provider == Provider.KUBERNETES })
            } finally {
                adapter.close()
            }
        }

    @Test
    fun `should preserve Kubernetes metadata when persisting`() =
        runBlocking {
            val client = KubernetesWorkloadTestBase.createKubernetesClient()
            val adapter = KubernetesAdapter(client = client, clusterName = "k3s-test-cluster")

            try {
                val discoveredServices = adapter.discoverServices(testOrg.id)

                discoveredServices.forEach { service ->
                    ServiceRepository.create(service)
                }

                // Find the api-gateway service and verify metadata
                val page = ServiceRepository.find(organizationId = testOrg.id, limit = 100)
                val apiGateway = page.items.find { it.name == "api-gateway" }

                assertNotNull(apiGateway)
                assertEquals("production", apiGateway.namespace)
                assertNotNull(apiGateway.metadata)
                assertEquals("ClusterIP", apiGateway.metadata!!["k8s.service.type"])
                assertEquals("api-gateway", apiGateway.metadata!!["app"])
                assertEquals("api-gateway", apiGateway.metadata!!["app.name"])
                assertEquals("1.5.0", apiGateway.metadata!!["version"])
                assertEquals("gateway", apiGateway.metadata!!["component"])
                assertEquals("backend", apiGateway.metadata!!["team"])
                assertEquals("API Gateway service", apiGateway.metadata!!["description"])
                assertEquals("backend-team@company.com", apiGateway.metadata!!["owner"])
            } finally {
                adapter.close()
            }
        }

    @Test
    fun `should support filtering persisted services by namespace`() =
        runBlocking {
            val client = KubernetesWorkloadTestBase.createKubernetesClient()
            val adapter = KubernetesAdapter(client = client, clusterName = "k3s-test-cluster")

            try {
                val discoveredServices = adapter.discoverServices(testOrg.id)

                discoveredServices.forEach { service ->
                    ServiceRepository.create(service)
                }

                // Filter by production namespace
                val productionServices =
                    ServiceRepository.find(
                        organizationId = testOrg.id,
                        namespace = "production",
                        limit = 100,
                    )

                assertEquals(1, productionServices.items.size)
                assertTrue(productionServices.items.all { it.namespace == "production" })
                assertTrue(productionServices.items.any { it.name == "api-gateway" })
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

                discoveredServices.forEach { service ->
                    ServiceRepository.create(service)
                }

                // Filter by cluster
                val clusterServices =
                    ServiceRepository.find(
                        organizationId = testOrg.id,
                        cluster = "k3s-test-cluster",
                        limit = 100,
                    )

                assertTrue(clusterServices.items.size >= 3)
                assertTrue(clusterServices.items.all { it.cluster == "k3s-test-cluster" })
            } finally {
                adapter.close()
            }
        }

    @Test
    fun `should handle upsert for re-discovering existing services`() =
        runBlocking {
            val client = KubernetesWorkloadTestBase.createKubernetesClient()
            val adapter = KubernetesAdapter(client = client, clusterName = "k3s-test-cluster")

            try {
                // First discovery and persist
                val firstDiscovery = adapter.discoverServices(testOrg.id)
                firstDiscovery.forEach { service ->
                    ServiceRepository.create(service)
                }

                val initialCount = ServiceRepository.find(organizationId = testOrg.id, limit = 100).items.size

                // Second discovery (simulating re-running discovery)
                val secondDiscovery = adapter.discoverServices(testOrg.id)

                // Upsert should update existing services
                secondDiscovery.forEach { service ->
                    ServiceRepository.upsert(service)
                }

                // Should still have the same number of services (no duplicates)
                val page = ServiceRepository.find(organizationId = testOrg.id, limit = 100)
                assertEquals(initialCount, page.items.size)
            } finally {
                adapter.close()
            }
        }

    @Test
    fun `should query specific service by name after persisting`() =
        runBlocking {
            val client = KubernetesWorkloadTestBase.createKubernetesClient()
            val adapter = KubernetesAdapter(client = client, clusterName = "k3s-test-cluster")

            try {
                val discoveredServices = adapter.discoverServices(testOrg.id)

                discoveredServices.forEach { service ->
                    ServiceRepository.create(service)
                }

                // Find API Gateway service
                val allServices = ServiceRepository.find(organizationId = testOrg.id, limit = 100)
                val apiGateway = allServices.items.find { it.name == "api-gateway" }

                assertNotNull(apiGateway)
                assertEquals("production", apiGateway.namespace)
                assertEquals(Provider.KUBERNETES, apiGateway.provider)
                assertNotNull(apiGateway.metadata)
                assertEquals("ClusterIP", apiGateway.metadata!!["k8s.service.type"])
                assertEquals("backend", apiGateway.metadata!!["team"])
                assertEquals("gateway", apiGateway.metadata!!["component"])
            } finally {
                adapter.close()
            }
        }

    @Test
    fun `should extract port information from real Kubernetes services`() =
        runBlocking {
            val client = KubernetesWorkloadTestBase.createKubernetesClient()
            val adapter = KubernetesAdapter(client = client, clusterName = "k3s-test-cluster")

            try {
                val discoveredServices = adapter.discoverServices(testOrg.id)

                discoveredServices.forEach { service ->
                    ServiceRepository.create(service)
                }

                // Find API Gateway service which has multiple ports
                val allServices = ServiceRepository.find(organizationId = testOrg.id, limit = 100)
                val apiGateway = allServices.items.find { it.name == "api-gateway" }

                assertNotNull(apiGateway)
                assertNotNull(apiGateway.metadata)
                val portsMetadata = apiGateway.metadata!!["k8s.ports"]
                assertNotNull(portsMetadata)
                assertTrue(portsMetadata.contains("http:8080/TCP"))
                assertTrue(portsMetadata.contains("grpc:9090/TCP"))
            } finally {
                adapter.close()
            }
        }

    @Test
    fun `should extract selector information from services`() =
        runBlocking {
            val client = KubernetesWorkloadTestBase.createKubernetesClient()
            val adapter = KubernetesAdapter(client = client, clusterName = "k3s-test-cluster")

            try {
                val discoveredServices = adapter.discoverServices(testOrg.id)

                discoveredServices.forEach { service ->
                    ServiceRepository.create(service)
                }

                // Find API Gateway service which has selector with multiple labels
                val allServices = ServiceRepository.find(organizationId = testOrg.id, limit = 100)
                val apiGateway = allServices.items.find { it.name == "api-gateway" }

                assertNotNull(apiGateway)
                assertNotNull(apiGateway.metadata)
                val selectorMetadata = apiGateway.metadata!!["k8s.selector"]
                assertNotNull(selectorMetadata)
                assertTrue(selectorMetadata.contains("app=api-gateway"))
                assertTrue(selectorMetadata.contains("version=v1"))
            } finally {
                adapter.close()
            }
        }

    @Test
    fun `should discover services from infrastructure namespace`() =
        runBlocking {
            val client = KubernetesWorkloadTestBase.createKubernetesClient()
            val adapter = KubernetesAdapter(client = client, clusterName = "k3s-test-cluster")

            try {
                val discoveredServices = adapter.discoverServices(testOrg.id)

                discoveredServices.forEach { service ->
                    ServiceRepository.create(service)
                }

                // Filter by infrastructure namespace
                val infraServices =
                    ServiceRepository.find(
                        organizationId = testOrg.id,
                        namespace = "infrastructure",
                        limit = 100,
                    )

                assertEquals(2, infraServices.items.size)
                assertTrue(infraServices.items.any { it.name == "redis" })
                assertTrue(infraServices.items.any { it.name == "postgresql" })
                assertTrue(infraServices.items.all { it.namespace == "infrastructure" })
            } finally {
                adapter.close()
            }
        }

    @Test
    fun `should discover only from specified namespaces when configured`() =
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
                assertTrue(discoveredServices.all { it.namespace == "production" })
                assertEquals(1, discoveredServices.size)
                assertTrue(discoveredServices.any { it.name == "api-gateway" })
            } finally {
                adapter.close()
            }
        }

    @Test
    fun `should extract Kubernetes UID for correlation`() =
        runBlocking {
            val client = KubernetesWorkloadTestBase.createKubernetesClient()
            val adapter = KubernetesAdapter(client = client, clusterName = "k3s-test-cluster")

            try {
                val discoveredServices = adapter.discoverServices(testOrg.id)

                discoveredServices.forEach { service ->
                    ServiceRepository.create(service)
                }

                // All services should have a k8s.uid metadata field
                val allServices = ServiceRepository.find(organizationId = testOrg.id, limit = 100)
                assertTrue(
                    allServices.items.all { service ->
                        service.metadata?.containsKey("k8s.uid") == true
                    },
                )
            } finally {
                adapter.close()
            }
        }

    @Test
    fun `should extract creation timestamp`() =
        runBlocking {
            val client = KubernetesWorkloadTestBase.createKubernetesClient()
            val adapter = KubernetesAdapter(client = client, clusterName = "k3s-test-cluster")

            try {
                val discoveredServices = adapter.discoverServices(testOrg.id)

                discoveredServices.forEach { service ->
                    ServiceRepository.create(service)
                }

                // All services should have a k8s.created.at metadata field
                val allServices = ServiceRepository.find(organizationId = testOrg.id, limit = 100)
                assertTrue(
                    allServices.items.all { service ->
                        service.metadata?.containsKey("k8s.created.at") == true
                    },
                )
            } finally {
                adapter.close()
            }
        }

    @Test
    fun `should persist services with unique IDs`() =
        runBlocking {
            val client = KubernetesWorkloadTestBase.createKubernetesClient()
            val adapter = KubernetesAdapter(client = client, clusterName = "k3s-test-cluster")

            try {
                val discoveredServices = adapter.discoverServices(testOrg.id)

                discoveredServices.forEach { service ->
                    ServiceRepository.create(service)
                }

                val page = ServiceRepository.find(organizationId = testOrg.id, limit = 100)
                val uniqueIds = page.items.map { it.id }.toSet()

                assertEquals(page.items.size, uniqueIds.size)
            } finally {
                adapter.close()
            }
        }

    @Test
    fun `should set discoveredAt and lastSeenAt timestamps`() =
        runBlocking {
            val client = KubernetesWorkloadTestBase.createKubernetesClient()
            val adapter = KubernetesAdapter(client = client, clusterName = "k3s-test-cluster")

            try {
                val discoveredServices = adapter.discoverServices(testOrg.id)

                // All services should have timestamps set
                assertTrue(discoveredServices.all { it.discoveredAt != null })
                assertTrue(discoveredServices.all { it.lastSeenAt != null })
            } finally {
                adapter.close()
            }
        }
}
