package com.platform.database

import com.platform.models.Organization
import com.platform.models.Service
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceRepositoryTest : DatabaseTestBase() {

    private lateinit var testOrg: Organization
    private lateinit var testOrg2: Organization

    @BeforeEach
    fun setupOrganizations() {
        testOrg = Organization(
            id = UUID.randomUUID().toString(),
            name = "Test Organization",
            createdAt = Instant.now()
        )
        testOrg2 = Organization(
            id = UUID.randomUUID().toString(),
            name = "Another Organization",
            createdAt = Instant.now()
        )
        OrganizationRepository.create(testOrg)
        OrganizationRepository.create(testOrg2)
    }

    private fun createTestService(
        id: String = UUID.randomUUID().toString(),
        organizationId: String = testOrg.id,
        cluster: String = "prod-cluster",
        namespace: String = "default",
        name: String = "test-service",
        provider: String? = "AWS",
        discoveredAt: Instant = Instant.now(),
        metadata: Map<String, String>? = null
    ) = Service(
        id = id,
        organizationId = organizationId,
        cluster = cluster,
        namespace = namespace,
        name = name,
        provider = provider,
        discoveredAt = discoveredAt,
        metadata = metadata
    )

    @Test
    fun `create should persist service`() {
        val service = createTestService(name = "order-service")

        val created = ServiceRepository.create(service)

        assertEquals(service.id, created.id)
        assertEquals("order-service", created.name)
        assertEquals(testOrg.id, created.organizationId)
    }

    @Test
    fun `create should persist service with metadata`() {
        val metadata = mapOf("version" to "1.0.0", "team" to "platform")
        val service = createTestService(name = "order-service", metadata = metadata)

        ServiceRepository.create(service)
        val found = ServiceRepository.findById(service.id)

        assertNotNull(found)
        assertNotNull(found.metadata)
        assertEquals("1.0.0", found.metadata!!["version"])
        assertEquals("platform", found.metadata!!["team"])
    }

    @Test
    fun `findById should return service when exists`() {
        val service = createTestService(name = "payment-service")
        ServiceRepository.create(service)

        val found = ServiceRepository.findById(service.id)

        assertNotNull(found)
        assertEquals(service.id, found.id)
        assertEquals("payment-service", found.name)
        assertEquals("prod-cluster", found.cluster)
        assertEquals("default", found.namespace)
    }

    @Test
    fun `findById should return null when not exists`() {
        val found = ServiceRepository.findById(UUID.randomUUID().toString())

        assertNull(found)
    }

    @Test
    fun `findAll should return all services`() {
        val service1 = createTestService(name = "service-1")
        val service2 = createTestService(name = "service-2")
        val service3 = createTestService(name = "service-3", organizationId = testOrg2.id)

        ServiceRepository.create(service1)
        ServiceRepository.create(service2)
        ServiceRepository.create(service3)

        val all = ServiceRepository.findAll()

        assertEquals(3, all.size)
    }

    @Test
    fun `findByOrganization should return only services for that org`() {
        val service1 = createTestService(name = "service-1", organizationId = testOrg.id)
        val service2 = createTestService(name = "service-2", organizationId = testOrg.id)
        val service3 = createTestService(name = "service-3", organizationId = testOrg2.id)

        ServiceRepository.create(service1)
        ServiceRepository.create(service2)
        ServiceRepository.create(service3)

        val orgServices = ServiceRepository.findByOrganization(testOrg.id)

        assertEquals(2, orgServices.size)
        assertTrue(orgServices.all { it.organizationId == testOrg.id })
    }

    @Test
    fun `findByCluster should return services in that cluster`() {
        val service1 = createTestService(name = "service-1", cluster = "prod-us-east")
        val service2 = createTestService(name = "service-2", cluster = "prod-us-east")
        val service3 = createTestService(name = "service-3", cluster = "prod-eu-west")

        ServiceRepository.create(service1)
        ServiceRepository.create(service2)
        ServiceRepository.create(service3)

        val clusterServices = ServiceRepository.findByCluster(testOrg.id, "prod-us-east")

        assertEquals(2, clusterServices.size)
        assertTrue(clusterServices.all { it.cluster == "prod-us-east" })
    }

    @Test
    fun `findByNamespace should return services in that namespace`() {
        val service1 = createTestService(name = "service-1", cluster = "prod", namespace = "payments")
        val service2 = createTestService(name = "service-2", cluster = "prod", namespace = "payments")
        val service3 = createTestService(name = "service-3", cluster = "prod", namespace = "orders")

        ServiceRepository.create(service1)
        ServiceRepository.create(service2)
        ServiceRepository.create(service3)

        val nsServices = ServiceRepository.findByNamespace(testOrg.id, "prod", "payments")

        assertEquals(2, nsServices.size)
        assertTrue(nsServices.all { it.namespace == "payments" })
    }

    @Test
    fun `delete should remove service`() {
        val service = createTestService()
        ServiceRepository.create(service)

        val deleted = ServiceRepository.delete(service.id)

        assertTrue(deleted)
        assertNull(ServiceRepository.findById(service.id))
    }

    @Test
    fun `delete should return false when service not exists`() {
        val deleted = ServiceRepository.delete(UUID.randomUUID().toString())

        assertTrue(!deleted)
    }

    @Test
    fun `upsert should create new service when not exists`() {
        val service = createTestService(name = "new-service")

        val result = ServiceRepository.upsert(service)

        assertNotNull(ServiceRepository.findById(result.id))
        assertEquals("new-service", result.name)
    }

    @Test
    fun `upsert should update existing service when identity matches`() {
        val original = createTestService(
            name = "my-service",
            cluster = "prod",
            namespace = "default",
            provider = "AWS",
            metadata = mapOf("version" to "1.0")
        )
        ServiceRepository.create(original)

        val updated = original.copy(
            id = UUID.randomUUID().toString(), // Different ID but same identity
            provider = "GCP",
            metadata = mapOf("version" to "2.0")
        )

        val result = ServiceRepository.upsert(updated)

        // Should return the original ID since identity matched
        assertEquals(original.id, result.id)

        val found = ServiceRepository.findById(original.id)
        assertNotNull(found)
        assertEquals("GCP", found.provider)
        assertEquals("2.0", found.metadata?.get("version"))
    }

    @Test
    fun `unique constraint should prevent duplicate service identity`() {
        val service1 = createTestService(
            name = "order-service",
            cluster = "prod",
            namespace = "orders"
        )
        val service2 = createTestService(
            name = "order-service",
            cluster = "prod",
            namespace = "orders"
        )

        ServiceRepository.create(service1)

        assertThrows<Exception> {
            ServiceRepository.create(service2)
        }
    }

    @Test
    fun `same service name allowed in different namespaces`() {
        val service1 = createTestService(
            name = "api-gateway",
            cluster = "prod",
            namespace = "ns-1"
        )
        val service2 = createTestService(
            name = "api-gateway",
            cluster = "prod",
            namespace = "ns-2"
        )

        ServiceRepository.create(service1)
        ServiceRepository.create(service2)

        val all = ServiceRepository.findAll()
        assertEquals(2, all.size)
    }

    @Test
    fun `same service name allowed in different clusters`() {
        val service1 = createTestService(
            name = "api-gateway",
            cluster = "cluster-1",
            namespace = "default"
        )
        val service2 = createTestService(
            name = "api-gateway",
            cluster = "cluster-2",
            namespace = "default"
        )

        ServiceRepository.create(service1)
        ServiceRepository.create(service2)

        val all = ServiceRepository.findAll()
        assertEquals(2, all.size)
    }

    @Test
    fun `same service name allowed in different organizations`() {
        val service1 = createTestService(
            name = "common-service",
            organizationId = testOrg.id
        )
        val service2 = createTestService(
            name = "common-service",
            organizationId = testOrg2.id
        )

        ServiceRepository.create(service1)
        ServiceRepository.create(service2)

        val all = ServiceRepository.findAll()
        assertEquals(2, all.size)
    }

    @Test
    fun `service with null provider should be persisted`() {
        val service = createTestService(name = "no-provider-service", provider = null)

        ServiceRepository.create(service)
        val found = ServiceRepository.findById(service.id)

        assertNotNull(found)
        assertNull(found.provider)
    }

    @Test
    fun `service with null metadata should be persisted`() {
        val service = createTestService(name = "no-metadata-service", metadata = null)

        ServiceRepository.create(service)
        val found = ServiceRepository.findById(service.id)

        assertNotNull(found)
        assertNull(found.metadata)
    }
}
