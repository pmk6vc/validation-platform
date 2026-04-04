package com.platform.database

import com.platform.models.Organization
import com.platform.models.Provider
import com.platform.models.Service
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceRepositoryTest : AppDatabaseTestBase() {
    private lateinit var testOrg: Organization
    private lateinit var testOrg2: Organization

    @BeforeEach
    fun setupOrganizations() {
        runBlocking {
            testOrg =
                Organization(
                    id = UUID.randomUUID().toString(),
                    name = "Test Organization",
                    createdAt = Instant.now(),
                )
            testOrg2 =
                Organization(
                    id = UUID.randomUUID().toString(),
                    name = "Another Organization",
                    createdAt = Instant.now(),
                )
            OrganizationRepository.create(testOrg)
            OrganizationRepository.create(testOrg2)
        }
    }

    private fun createTestService(
        id: String = UUID.randomUUID().toString(),
        organizationId: String = testOrg.id,
        cluster: String = "prod-cluster",
        namespace: String = "default",
        name: String = "test-service",
        provider: Provider = Provider.KUBERNETES,
        discoveredAt: Instant = Instant.now(),
        lastSeenAt: Instant = Instant.now(),
        metadata: Map<String, String>? = null,
    ) = Service(
        id = id,
        organizationId = organizationId,
        cluster = cluster,
        namespace = namespace,
        name = name,
        provider = provider,
        discoveredAt = discoveredAt,
        lastSeenAt = lastSeenAt,
        metadata = metadata,
    )

    @Test
    fun `create should persist service`() =
        runBlocking {
            val service = createTestService(name = "order-service")

            val created = ServiceRepository.create(service)

            assertEquals(service.id, created.id)
            assertEquals("order-service", created.name)
            assertEquals(testOrg.id, created.organizationId)
        }

    @Test
    fun `create should persist service with metadata`() =
        runBlocking {
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
    fun `findById should return service when exists`() =
        runBlocking {
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
    fun `findById should return null when not exists`() =
        runBlocking {
            val found = ServiceRepository.findById(UUID.randomUUID().toString())

            assertNull(found)
        }

    @Test
    fun `find with organizationId should return only services for that org`() =
        runBlocking {
            val service1 = createTestService(name = "service-1", organizationId = testOrg.id)
            val service2 = createTestService(name = "service-2", organizationId = testOrg.id)
            val service3 = createTestService(name = "service-3", organizationId = testOrg2.id)

            ServiceRepository.create(service1)
            ServiceRepository.create(service2)
            ServiceRepository.create(service3)

            val page = ServiceRepository.find(organizationId = testOrg.id)

            assertEquals(2, page.items.size)
            assertTrue(page.items.all { it.organizationId == testOrg.id })
        }

    @Test
    fun `find with cluster should return services in that cluster`() =
        runBlocking {
            val service1 = createTestService(name = "service-1", cluster = "prod-us-east")
            val service2 = createTestService(name = "service-2", cluster = "prod-us-east")
            val service3 = createTestService(name = "service-3", cluster = "prod-eu-west")

            ServiceRepository.create(service1)
            ServiceRepository.create(service2)
            ServiceRepository.create(service3)

            val page = ServiceRepository.find(cluster = "prod-us-east")

            assertEquals(2, page.items.size)
            assertTrue(page.items.all { it.cluster == "prod-us-east" })
        }

    @Test
    fun `find with namespace should return services in that namespace`() =
        runBlocking {
            val service1 = createTestService(name = "service-1", cluster = "prod", namespace = "payments")
            val service2 = createTestService(name = "service-2", cluster = "staging", namespace = "payments")
            val service3 = createTestService(name = "service-3", cluster = "prod", namespace = "orders")

            ServiceRepository.create(service1)
            ServiceRepository.create(service2)
            ServiceRepository.create(service3)

            val page = ServiceRepository.find(namespace = "payments")

            assertEquals(2, page.items.size)
            assertTrue(page.items.all { it.namespace == "payments" })
        }

    @Test
    fun `find with multiple filters should combine them with AND`() =
        runBlocking {
            val service1 = createTestService(name = "service-1", cluster = "prod", namespace = "payments")
            val service2 = createTestService(name = "service-2", cluster = "prod", namespace = "orders")
            val service3 = createTestService(name = "service-3", cluster = "staging", namespace = "payments")

            ServiceRepository.create(service1)
            ServiceRepository.create(service2)
            ServiceRepository.create(service3)

            val page = ServiceRepository.find(cluster = "prod", namespace = "payments")

            assertEquals(1, page.items.size)
            assertEquals("service-1", page.items[0].name)
        }

    @Test
    fun `find with limit should return at most limit items`() =
        runBlocking {
            repeat(5) { i ->
                ServiceRepository.create(createTestService(name = "service-$i"))
            }

            val page = ServiceRepository.find(limit = 3)

            assertEquals(3, page.items.size)
        }

    @Test
    fun `find should return nextCursor when more items exist`() {
        runBlocking {
            repeat(5) { i ->
                ServiceRepository.create(createTestService(name = "service-$i"))
            }

            val page = ServiceRepository.find(limit = 3)

            assertEquals(3, page.items.size)
            assertNotNull(page.nextCursor)
        }
    }

    @Test
    fun `find should return null nextCursor when no more items`() =
        runBlocking {
            repeat(3) { i ->
                ServiceRepository.create(createTestService(name = "service-$i"))
            }

            val page = ServiceRepository.find(limit = 5)

            assertEquals(3, page.items.size)
            assertNull(page.nextCursor)
        }

    @Test
    fun `find with cursor should return items after cursor`() =
        runBlocking {
            repeat(5) { i ->
                ServiceRepository.create(createTestService(name = "service-$i"))
            }

            val firstPage = ServiceRepository.find(limit = 2)
            assertEquals(2, firstPage.items.size)
            assertNotNull(firstPage.nextCursor)

            val secondPage = ServiceRepository.find(limit = 2, cursor = firstPage.nextCursor)
            assertEquals(2, secondPage.items.size)
            assertNotNull(secondPage.nextCursor)

            // Verify no overlap between pages
            val firstPageIds = firstPage.items.map { it.id }.toSet()
            val secondPageIds = secondPage.items.map { it.id }.toSet()
            assertTrue(firstPageIds.intersect(secondPageIds).isEmpty())

            val thirdPage = ServiceRepository.find(limit = 2, cursor = secondPage.nextCursor)
            assertEquals(1, thirdPage.items.size)
            assertNull(thirdPage.nextCursor)
        }

    @Test
    fun `find should enforce max page size`() =
        runBlocking {
            repeat(150) { i ->
                ServiceRepository.create(createTestService(name = "service-$i"))
            }

            val page = ServiceRepository.find(limit = 200)

            assertEquals(ServiceRepository.MAX_PAGE_SIZE, page.items.size)
        }

    @Test
    fun `delete should remove service`() =
        runBlocking {
            val service = createTestService()
            ServiceRepository.create(service)

            val deleted = ServiceRepository.delete(service.id)

            assertTrue(deleted)
            assertNull(ServiceRepository.findById(service.id))
        }

    @Test
    fun `delete should return false when service not exists`() =
        runBlocking {
            val deleted = ServiceRepository.delete(UUID.randomUUID().toString())

            assertTrue(!deleted)
        }

    @Test
    fun `upsert should create new service when not exists`() =
        runBlocking {
            val service = createTestService(name = "new-service")

            val result = ServiceRepository.upsert(service)

            assertNotNull(ServiceRepository.findById(result.id))
            assertEquals("new-service", result.name)
        }

    @Test
    fun `upsert should update mutable fields and preserve identity fields when match exists`() =
        runBlocking {
            val originalTime = Instant.parse("2024-01-01T00:00:00Z")
            val original =
                createTestService(
                    name = "my-service",
                    cluster = "prod",
                    namespace = "default",
                    provider = Provider.KUBERNETES,
                    discoveredAt = originalTime,
                    lastSeenAt = originalTime,
                    metadata = mapOf("version" to "1.0", "team" to "platform"),
                )
            ServiceRepository.create(original)

            val laterTime = Instant.parse("2024-06-15T12:00:00Z")
            val updated =
                original.copy(
                    id = UUID.randomUUID().toString(), // Different ID but same identity
                    provider = Provider.KUBERNETES,
                    lastSeenAt = laterTime,
                    metadata = mapOf("version" to "2.0", "region" to "us-east"),
                )

            val result = ServiceRepository.upsert(updated)

            // Should return the original ID since identity matched
            assertEquals(original.id, result.id)

            val found = ServiceRepository.findById(original.id)
            assertNotNull(found)

            // Identity fields should be unchanged
            assertEquals(original.organizationId, found.organizationId)
            assertEquals("prod", found.cluster)
            assertEquals("default", found.namespace)
            assertEquals("my-service", found.name)
            assertEquals(originalTime, found.discoveredAt)

            // Mutable fields should be updated
            assertEquals(Provider.KUBERNETES, found.provider)
            assertEquals(laterTime, found.lastSeenAt)
            assertEquals(mapOf("version" to "2.0", "region" to "us-east"), found.metadata)
        }

    @Test
    fun `unique constraint should prevent duplicate service identity`() {
        runBlocking {
            val service1 =
                createTestService(
                    name = "order-service",
                    cluster = "prod",
                    namespace = "orders",
                )
            val service2 =
                createTestService(
                    name = "order-service",
                    cluster = "prod",
                    namespace = "orders",
                )

            ServiceRepository.create(service1)

            assertThrows<Exception> {
                runBlocking { ServiceRepository.create(service2) }
            }
        }
    }

    @Test
    fun `same service name allowed in different namespaces`() =
        runBlocking {
            val service1 =
                createTestService(
                    name = "api-gateway",
                    cluster = "prod",
                    namespace = "ns-1",
                )
            val service2 =
                createTestService(
                    name = "api-gateway",
                    cluster = "prod",
                    namespace = "ns-2",
                )

            ServiceRepository.create(service1)
            ServiceRepository.create(service2)

            val page = ServiceRepository.find(limit = 100)
            assertEquals(2, page.items.size)
        }

    @Test
    fun `same service name allowed in different clusters`() =
        runBlocking {
            val service1 =
                createTestService(
                    name = "api-gateway",
                    cluster = "cluster-1",
                    namespace = "default",
                )
            val service2 =
                createTestService(
                    name = "api-gateway",
                    cluster = "cluster-2",
                    namespace = "default",
                )

            ServiceRepository.create(service1)
            ServiceRepository.create(service2)

            val page = ServiceRepository.find(limit = 100)
            assertEquals(2, page.items.size)
        }

    @Test
    fun `same service name allowed in different organizations`() =
        runBlocking {
            val service1 =
                createTestService(
                    name = "common-service",
                    organizationId = testOrg.id,
                )
            val service2 =
                createTestService(
                    name = "common-service",
                    organizationId = testOrg2.id,
                )

            ServiceRepository.create(service1)
            ServiceRepository.create(service2)

            val page = ServiceRepository.find(limit = 100)
            assertEquals(2, page.items.size)
        }

    @Test
    fun `service with UNKNOWN provider should be persisted`() =
        runBlocking {
            val service = createTestService(name = "unknown-provider-service", provider = Provider.UNKNOWN)

            ServiceRepository.create(service)
            val found = ServiceRepository.findById(service.id)

            assertNotNull(found)
            assertEquals(Provider.UNKNOWN, found.provider)
        }

    @Test
    fun `service with null metadata should be persisted`() =
        runBlocking {
            val service = createTestService(name = "no-metadata-service", metadata = null)

            ServiceRepository.create(service)
            val found = ServiceRepository.findById(service.id)

            assertNotNull(found)
            assertNull(found.metadata)
        }
}
