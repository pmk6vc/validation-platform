package com.platform.database

import com.platform.models.Organization
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrganizationRepositoryTest : DatabaseTestBase() {
    private fun createTestOrganization(
        id: String = UUID.randomUUID().toString(),
        name: String = "Test Organization",
        createdAt: Instant = Instant.now(),
    ) = Organization(id = id, name = name, createdAt = createdAt)

    @Test
    fun `create should persist organization`() =
        runBlocking {
            val org = createTestOrganization(name = "Acme Corp")

            val created = OrganizationRepository.create(org)

            assertEquals(org.id, created.id)
            assertEquals("Acme Corp", created.name)
        }

    @Test
    fun `findById should return organization when exists`() =
        runBlocking {
            val org = createTestOrganization(name = "Acme Corp")
            OrganizationRepository.create(org)

            val found = OrganizationRepository.findById(org.id)

            assertNotNull(found)
            assertEquals(org.id, found.id)
            assertEquals("Acme Corp", found.name)
        }

    @Test
    fun `findById should return null when not exists`() =
        runBlocking {
            val found = OrganizationRepository.findById(UUID.randomUUID().toString())

            assertNull(found)
        }

    @Test
    fun `delete should remove organization`() =
        runBlocking {
            val org = createTestOrganization()
            OrganizationRepository.create(org)

            val deleted = OrganizationRepository.delete(org.id)

            assertTrue(deleted)
            assertNull(OrganizationRepository.findById(org.id))
        }

    @Test
    fun `delete should return false when organization not exists`() =
        runBlocking {
            val deleted = OrganizationRepository.delete(UUID.randomUUID().toString())

            assertTrue(!deleted)
        }

    @Test
    fun `create should fail with duplicate id`() {
        runBlocking {
            val id = UUID.randomUUID().toString()
            val org1 = createTestOrganization(id = id, name = "Org 1")
            val org2 = createTestOrganization(id = id, name = "Org 2")

            OrganizationRepository.create(org1)

            assertThrows<Exception> {
                runBlocking { OrganizationRepository.create(org2) }
            }
        }
    }

    @Test
    fun `find with limit should return at most limit items`() =
        runBlocking {
            repeat(5) { i ->
                OrganizationRepository.create(createTestOrganization(name = "Org $i"))
            }

            val page = OrganizationRepository.find(limit = 3)

            assertEquals(3, page.items.size)
        }

    @Test
    fun `find should return nextCursor when more items exist`() {
        runBlocking {
            repeat(5) { i ->
                OrganizationRepository.create(createTestOrganization(name = "Org $i"))
            }

            val page = OrganizationRepository.find(limit = 3)

            assertEquals(3, page.items.size)
            assertNotNull(page.nextCursor)
        }
    }

    @Test
    fun `find should return null nextCursor when no more items`() =
        runBlocking {
            repeat(3) { i ->
                OrganizationRepository.create(createTestOrganization(name = "Org $i"))
            }

            val page = OrganizationRepository.find(limit = 5)

            assertEquals(3, page.items.size)
            assertNull(page.nextCursor)
        }

    @Test
    fun `find with cursor should return items after cursor`() =
        runBlocking {
            repeat(5) { i ->
                OrganizationRepository.create(createTestOrganization(name = "Org $i"))
            }

            val firstPage = OrganizationRepository.find(limit = 2)
            assertEquals(2, firstPage.items.size)
            assertNotNull(firstPage.nextCursor)

            val secondPage = OrganizationRepository.find(limit = 2, cursor = firstPage.nextCursor)
            assertEquals(2, secondPage.items.size)
            assertNotNull(secondPage.nextCursor)

            // Verify no overlap between pages
            val firstPageIds = firstPage.items.map { it.id }.toSet()
            val secondPageIds = secondPage.items.map { it.id }.toSet()
            assertTrue(firstPageIds.intersect(secondPageIds).isEmpty())

            val thirdPage = OrganizationRepository.find(limit = 2, cursor = secondPage.nextCursor)
            assertEquals(1, thirdPage.items.size)
            assertNull(thirdPage.nextCursor)
        }

    @Test
    fun `find should enforce max page size`() =
        runBlocking {
            repeat(150) { i ->
                OrganizationRepository.create(createTestOrganization(name = "Org $i"))
            }

            val page = OrganizationRepository.find(limit = 200)

            assertEquals(OrganizationRepository.MAX_PAGE_SIZE, page.items.size)
        }
}
