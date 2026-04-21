package com.platform.adapters

import com.platform.database.AppDatabaseTestBase
import com.platform.database.OrganizationRepository
import com.platform.database.ServiceRepository
import com.platform.models.Organization
import com.platform.models.OrganizationId
import com.platform.models.Provider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration test demonstrating the full adapter workflow:
 * 1. Adapter discovers services from its source
 * 2. Services are persisted to the database via repository
 * 3. Services can be queried back from the database
 */
class ManualSeedAdapterIntegrationTest : AppDatabaseTestBase() {
    private val adapter = ManualSeedAdapter()
    private lateinit var testOrg: Organization

    @BeforeEach
    fun setupOrganization() {
        runBlocking {
            testOrg =
                Organization(
                    id = OrganizationId.generate(),
                    name = "Test Organization",
                    createdAt = Instant.now(),
                )
            OrganizationRepository.create(testOrg)
        }
    }

    @Test
    fun `should discover and persist seed services to database`() =
        runBlocking {
            // Discover services from adapter
            val discoveredServices = adapter.discoverServices(testOrg.id)

            // Persist all discovered services
            discoveredServices.forEach { service ->
                ServiceRepository.create(service)
            }

            // Verify services were persisted
            val page = ServiceRepository.find(organizationId = testOrg.id, limit = 100)
            assertEquals(discoveredServices.size, page.items.size)
        }

    @Test
    fun `should preserve provider information when persisting`() =
        runBlocking {
            val discoveredServices = adapter.discoverServices(testOrg.id)

            discoveredServices.forEach { service ->
                ServiceRepository.create(service)
            }

            val page = ServiceRepository.find(organizationId = testOrg.id, limit = 100)
            assertTrue(page.items.all { it.provider == Provider.MANUAL_SEED })
        }

    @Test
    fun `should preserve metadata when persisting`() =
        runBlocking {
            val discoveredServices = adapter.discoverServices(testOrg.id)

            discoveredServices.forEach { service ->
                ServiceRepository.create(service)
            }

            // Find a specific service and verify metadata
            val orderService = discoveredServices.find { it.name == "order-service" }
            assertNotNull(orderService)

            val persisted = ServiceRepository.findById(orderService.id)
            assertNotNull(persisted)
            assertNotNull(persisted.metadata)
            assertEquals(orderService.metadata, persisted.metadata)
        }

    @Test
    fun `should support filtering persisted services by namespace`() =
        runBlocking {
            val discoveredServices = adapter.discoverServices(testOrg.id)

            discoveredServices.forEach { service ->
                ServiceRepository.create(service)
            }

            // Filter by backend namespace
            val backendServices =
                ServiceRepository.find(
                    organizationId = testOrg.id,
                    namespace = "backend",
                    limit = 100,
                )

            assertTrue(backendServices.items.isNotEmpty())
            assertTrue(backendServices.items.all { it.namespace == "backend" })
            assertTrue(backendServices.items.any { it.name == "order-service" })
            assertTrue(backendServices.items.any { it.name == "payment-service" })
        }

    @Test
    fun `should support filtering persisted services by cluster`() =
        runBlocking {
            val discoveredServices = adapter.discoverServices(testOrg.id)

            discoveredServices.forEach { service ->
                ServiceRepository.create(service)
            }

            // Filter by cluster
            val clusterServices =
                ServiceRepository.find(
                    organizationId = testOrg.id,
                    cluster = "prod-us-east",
                    limit = 100,
                )

            assertEquals(discoveredServices.size, clusterServices.items.size)
            assertTrue(clusterServices.items.all { it.cluster == "prod-us-east" })
        }

    @Test
    fun `should handle upsert for re-discovering existing services`() =
        runBlocking {
            // First discovery and persist
            val firstDiscovery = adapter.discoverServices(testOrg.id)
            firstDiscovery.forEach { service ->
                ServiceRepository.create(service)
            }

            // Second discovery (simulating re-running discovery)
            val secondDiscovery = adapter.discoverServices(testOrg.id)

            // Upsert should update existing services
            secondDiscovery.forEach { service ->
                ServiceRepository.upsert(service)
            }

            // Should still have the same number of services (no duplicates)
            val page = ServiceRepository.find(organizationId = testOrg.id, limit = 100)
            assertEquals(firstDiscovery.size, page.items.size)
        }

    @Test
    fun `should query specific service by name after persisting`() =
        runBlocking {
            val discoveredServices = adapter.discoverServices(testOrg.id)

            discoveredServices.forEach { service ->
                ServiceRepository.create(service)
            }

            // Find Kafka service
            val allServices = ServiceRepository.find(organizationId = testOrg.id, limit = 100)
            val kafka = allServices.items.find { it.name == "kafka" }

            assertNotNull(kafka)
            assertEquals("messaging", kafka.namespace)
            assertEquals(Provider.MANUAL_SEED, kafka.provider)
            assertNotNull(kafka.metadata)
            assertTrue(kafka.metadata!!.containsKey("type"))
            assertEquals("kafka", kafka.metadata!!["type"])
        }
}
