package com.platform.adapters

import com.platform.models.Provider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ManualSeedAdapterTest {
    private val adapter = ManualSeedAdapter()

    @Test
    fun `discoverServices should return predefined seed services`() =
        runBlocking {
            val organizationId = UUID.randomUUID().toString()

            val services = adapter.discoverServices(organizationId)

            assertTrue(services.isNotEmpty())
            assertTrue(services.all { it.organizationId == organizationId })
        }

    @Test
    fun `all seed services should have MANUAL_SEED provider`() =
        runBlocking {
            val organizationId = UUID.randomUUID().toString()

            val services = adapter.discoverServices(organizationId)

            assertTrue(services.all { it.provider == Provider.MANUAL_SEED })
        }

    @Test
    fun `seed data should include frontend services`() =
        runBlocking {
            val organizationId = UUID.randomUUID().toString()

            val services = adapter.discoverServices(organizationId)

            val frontendServices = services.filter { it.namespace == "frontend" }
            assertTrue(frontendServices.isNotEmpty())
            assertTrue(frontendServices.any { it.name == "web-ui" })
            assertTrue(frontendServices.any { it.name == "mobile-api-gateway" })
        }

    @Test
    fun `seed data should include backend services`() =
        runBlocking {
            val organizationId = UUID.randomUUID().toString()

            val services = adapter.discoverServices(organizationId)

            val backendServices = services.filter { it.namespace == "backend" }
            assertTrue(backendServices.isNotEmpty())
            assertTrue(backendServices.any { it.name == "order-service" })
            assertTrue(backendServices.any { it.name == "payment-service" })
            assertTrue(backendServices.any { it.name == "user-service" })
        }

    @Test
    fun `seed data should include messaging layer`() =
        runBlocking {
            val organizationId = UUID.randomUUID().toString()

            val services = adapter.discoverServices(organizationId)

            val messagingServices = services.filter { it.namespace == "messaging" }
            assertTrue(messagingServices.isNotEmpty())
            assertTrue(messagingServices.any { it.name == "kafka" })
        }

    @Test
    fun `seed data should include data layer services`() =
        runBlocking {
            val organizationId = UUID.randomUUID().toString()

            val services = adapter.discoverServices(organizationId)

            val dataServices = services.filter { it.namespace == "data" }
            assertTrue(dataServices.isNotEmpty())
            assertTrue(dataServices.any { it.name == "orders-db" })
            assertTrue(dataServices.any { it.name == "session-cache" })
        }

    @Test
    fun `all services should be in prod-us-east cluster`() =
        runBlocking {
            val organizationId = UUID.randomUUID().toString()

            val services = adapter.discoverServices(organizationId)

            assertTrue(services.all { it.cluster == "prod-us-east" })
        }

    @Test
    fun `all services should have metadata`() =
        runBlocking {
            val organizationId = UUID.randomUUID().toString()

            val services = adapter.discoverServices(organizationId)

            assertTrue(services.all { it.metadata != null && it.metadata!!.isNotEmpty() })
        }

    @Test
    fun `kafka service should have topics metadata`() =
        runBlocking {
            val organizationId = UUID.randomUUID().toString()

            val services = adapter.discoverServices(organizationId)

            val kafka = services.find { it.name == "kafka" }
            assertNotNull(kafka)
            assertNotNull(kafka.metadata)
            assertTrue(kafka.metadata!!.containsKey("topics"))
            assertTrue(kafka.metadata!!["topics"]!!.contains("order-events"))
        }

    @Test
    fun `services should have unique IDs`() =
        runBlocking {
            val organizationId = UUID.randomUUID().toString()

            val services = adapter.discoverServices(organizationId)

            val uniqueIds = services.map { it.id }.toSet()
            assertEquals(services.size, uniqueIds.size)
        }

    @Test
    fun `discoveredAt and lastSeenAt should be set`() =
        runBlocking {
            val organizationId = UUID.randomUUID().toString()

            val services = adapter.discoverServices(organizationId)

            assertTrue(services.all { it.discoveredAt != null })
            assertTrue(services.all { it.lastSeenAt != null })
        }

    @Test
    fun `different organization IDs should produce different service instances`() =
        runBlocking {
            val org1 = UUID.randomUUID().toString()
            val org2 = UUID.randomUUID().toString()

            val services1 = adapter.discoverServices(org1)
            val services2 = adapter.discoverServices(org2)

            // Same number of services
            assertEquals(services1.size, services2.size)

            // Different organization IDs
            assertTrue(services1.all { it.organizationId == org1 })
            assertTrue(services2.all { it.organizationId == org2 })

            // Different service IDs (new UUIDs generated each time)
            val ids1 = services1.map { it.id }.toSet()
            val ids2 = services2.map { it.id }.toSet()
            assertTrue(ids1.intersect(ids2).isEmpty())
        }
}
