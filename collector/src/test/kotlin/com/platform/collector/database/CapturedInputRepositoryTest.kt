package com.platform.collector.database

import com.platform.collector.models.CapturedInput
import com.platform.collector.models.CapturedInputId
import com.platform.collector.models.InputType
import com.platform.collector.models.ServiceId
import com.platform.shared.models.OrganizationId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapturedInputRepositoryTest : CollectorDatabaseTestBase() {
    private val testOrgId: OrganizationId = OrganizationId(UUID.randomUUID().toString())
    private val otherOrgId: OrganizationId = OrganizationId(UUID.randomUUID().toString())
    private val testServiceId: ServiceId = ServiceId(UUID.randomUUID().toString())
    private val otherServiceId: ServiceId = ServiceId(UUID.randomUUID().toString())

    private fun createTestInput(
        id: String = UUID.randomUUID().toString(),
        serviceId: ServiceId = testServiceId,
        organizationId: OrganizationId = testOrgId,
        inputType: InputType = InputType.HTTP,
        method: String = "GET",
        url: String = "/api/orders",
        requestHeaders: Map<String, String>? = null,
        requestBody: String? = null,
        responseStatus: Int = 200,
        responseHeaders: Map<String, String>? = null,
        responseBody: String? = """{"orders":[]}""",
        latencyMs: Long? = 42L,
        sourceIp: String? = "10.0.0.1",
        destinationIp: String? = "10.0.0.2",
        capturedAt: Instant = Instant.now(),
    ) = CapturedInput(
        id = CapturedInputId(id),
        serviceId = serviceId,
        organizationId = organizationId,
        inputType = inputType,
        method = method,
        url = url,
        requestHeaders = requestHeaders,
        requestBody = requestBody,
        responseStatus = responseStatus,
        responseHeaders = responseHeaders,
        responseBody = responseBody,
        latencyMs = latencyMs,
        sourceIp = sourceIp,
        destinationIp = destinationIp,
        capturedAt = capturedAt,
    )

    @Test
    fun `create should persist captured input`() =
        runBlocking {
            val input = createTestInput(method = "GET", url = "/api/orders")

            val created = CapturedInputRepository.create(input)

            assertEquals(input.id, created.id)
            assertEquals(testServiceId, created.serviceId)
            assertEquals(testOrgId, created.organizationId)
            assertEquals(InputType.HTTP, created.inputType)
        }

    @Test
    fun `findById should return captured input when exists and org matches`() =
        runBlocking {
            val input =
                createTestInput(
                    method = "POST",
                    url = "/api/orders",
                    requestBody = """{"item":"book"}""",
                    responseStatus = 201,
                    latencyMs = 125L,
                )
            CapturedInputRepository.create(input)

            val found = CapturedInputRepository.findById(id = input.id, organizationId = testOrgId)

            assertNotNull(found)
            assertEquals(input.id, found.id)
            assertEquals("POST", found.method)
            assertEquals("/api/orders", found.url)
            assertEquals("""{"item":"book"}""", found.requestBody)
            assertEquals(201, found.responseStatus)
            assertEquals(125L, found.latencyMs)
        }

    @Test
    fun `findById should return null when not exists`() =
        runBlocking {
            val found =
                CapturedInputRepository.findById(
                    id = CapturedInputId(UUID.randomUUID().toString()),
                    organizationId = testOrgId,
                )

            assertNull(found)
        }

    @Test
    fun `findById should return null when org does not match`() =
        runBlocking {
            val input = createTestInput(organizationId = testOrgId)
            CapturedInputRepository.create(input)

            // Looking up with a different org returns null — no info leak
            val found = CapturedInputRepository.findById(id = input.id, organizationId = otherOrgId)

            assertNull(found)
        }

    @Test
    fun `create should persist all nullable fields when provided`() =
        runBlocking {
            val requestHeaders = mapOf("Authorization" to "Bearer token", "Content-Type" to "application/json")
            val responseHeaders = mapOf("Content-Type" to "application/json", "X-Request-Id" to "abc123")
            val input =
                createTestInput(
                    requestHeaders = requestHeaders,
                    responseHeaders = responseHeaders,
                    sourceIp = "192.168.1.10",
                    destinationIp = "192.168.1.20",
                )
            CapturedInputRepository.create(input)

            val found = CapturedInputRepository.findById(id = input.id, organizationId = testOrgId)

            assertNotNull(found)
            assertEquals(requestHeaders, found.requestHeaders)
            assertEquals(responseHeaders, found.responseHeaders)
            assertEquals("192.168.1.10", found.sourceIp)
            assertEquals("192.168.1.20", found.destinationIp)
        }

    @Test
    fun `create should persist null optional fields`() =
        runBlocking {
            val input =
                createTestInput(
                    requestHeaders = null,
                    requestBody = null,
                    responseHeaders = null,
                    responseBody = null,
                    latencyMs = null,
                    sourceIp = null,
                    destinationIp = null,
                )
            CapturedInputRepository.create(input)

            val found = CapturedInputRepository.findById(id = input.id, organizationId = testOrgId)

            assertNotNull(found)
            assertNull(found.requestHeaders)
            assertNull(found.requestBody)
            assertNull(found.responseHeaders)
            assertNull(found.responseBody)
            assertNull(found.latencyMs)
            assertNull(found.sourceIp)
            assertNull(found.destinationIp)
        }

    @Test
    fun `createBatch should persist all inputs`() =
        runBlocking {
            val inputs =
                (1..5).map { i ->
                    createTestInput(url = "/api/orders/$i")
                }

            CapturedInputRepository.createBatch(inputs)

            val page = CapturedInputRepository.find(organizationId = testOrgId, serviceId = testServiceId)
            assertEquals(5, page.items.size)
        }

    @Test
    fun `createBatch should return the same inputs`() =
        runBlocking {
            val inputs = (1..3).map { createTestInput() }

            val result = CapturedInputRepository.createBatch(inputs)

            assertEquals(inputs.map { it.id }.toSet(), result.map { it.id }.toSet())
        }

    @Test
    fun `find with serviceId should return only inputs for that service`() =
        runBlocking {
            val input1 = createTestInput(serviceId = testServiceId)
            val input2 = createTestInput(serviceId = testServiceId)
            val input3 = createTestInput(serviceId = otherServiceId)
            CapturedInputRepository.create(input1)
            CapturedInputRepository.create(input2)
            CapturedInputRepository.create(input3)

            val page = CapturedInputRepository.find(organizationId = testOrgId, serviceId = testServiceId)

            assertEquals(2, page.items.size)
            assertTrue(page.items.all { it.serviceId == testServiceId })
        }

    @Test
    fun `find scoped to org should not return inputs from other orgs`() =
        runBlocking {
            val input1 = createTestInput(organizationId = testOrgId)
            val input2 = createTestInput(organizationId = otherOrgId)
            CapturedInputRepository.create(input1)
            CapturedInputRepository.create(input2)

            val page = CapturedInputRepository.find(organizationId = testOrgId)

            assertEquals(1, page.items.size)
            assertEquals(testOrgId, page.items[0].organizationId)
        }

    @Test
    fun `find with inputType should return only inputs of that type`() =
        runBlocking {
            val httpInput1 = createTestInput(inputType = InputType.HTTP)
            val httpInput2 = createTestInput(inputType = InputType.HTTP)
            CapturedInputRepository.create(httpInput1)
            CapturedInputRepository.create(httpInput2)

            val page = CapturedInputRepository.find(organizationId = testOrgId, inputType = InputType.HTTP)

            assertEquals(2, page.items.size)
            assertTrue(page.items.all { it.inputType == InputType.HTTP })
        }

    @Test
    fun `find with multiple filters should combine them with AND`() =
        runBlocking {
            val match =
                createTestInput(
                    serviceId = testServiceId,
                    inputType = InputType.HTTP,
                )
            val wrongService =
                createTestInput(
                    serviceId = otherServiceId,
                    inputType = InputType.HTTP,
                )
            CapturedInputRepository.create(match)
            CapturedInputRepository.create(wrongService)

            val page =
                CapturedInputRepository.find(
                    organizationId = testOrgId,
                    serviceId = testServiceId,
                    inputType = InputType.HTTP,
                )

            assertEquals(1, page.items.size)
            assertEquals(match.id, page.items[0].id)
        }

    @Test
    fun `find with limit should return at most limit items`() =
        runBlocking {
            repeat(5) { CapturedInputRepository.create(createTestInput()) }

            val page = CapturedInputRepository.find(organizationId = testOrgId, limit = 3)

            assertEquals(3, page.items.size)
        }

    @Test
    fun `find should return nextCursor when more items exist`() =
        runBlocking {
            repeat(5) { CapturedInputRepository.create(createTestInput()) }

            val page = CapturedInputRepository.find(organizationId = testOrgId, limit = 3)

            assertEquals(3, page.items.size)
            assertNotNull(page.nextCursor)
        }

    @Test
    fun `find should return null nextCursor when no more items`() =
        runBlocking {
            repeat(3) { CapturedInputRepository.create(createTestInput()) }

            val page = CapturedInputRepository.find(organizationId = testOrgId, limit = 5)

            assertEquals(3, page.items.size)
            assertNull(page.nextCursor)
        }

    @Test
    fun `find with cursor should return items after cursor`() =
        runBlocking {
            repeat(5) { CapturedInputRepository.create(createTestInput()) }

            val firstPage = CapturedInputRepository.find(organizationId = testOrgId, limit = 2)
            assertEquals(2, firstPage.items.size)
            assertNotNull(firstPage.nextCursor)

            val secondPage =
                CapturedInputRepository.find(organizationId = testOrgId, limit = 2, cursor = firstPage.nextCursor)
            assertEquals(2, secondPage.items.size)
            assertNotNull(secondPage.nextCursor)

            val firstPageIds = firstPage.items.map { it.id }.toSet()
            val secondPageIds = secondPage.items.map { it.id }.toSet()
            assertTrue(firstPageIds.intersect(secondPageIds).isEmpty())

            val thirdPage =
                CapturedInputRepository.find(organizationId = testOrgId, limit = 2, cursor = secondPage.nextCursor)
            assertEquals(1, thirdPage.items.size)
            assertNull(thirdPage.nextCursor)
        }

    @Test
    fun `find should enforce max page size`() =
        runBlocking {
            repeat(150) { CapturedInputRepository.create(createTestInput()) }

            val page = CapturedInputRepository.find(organizationId = testOrgId, limit = 200)

            assertEquals(CapturedInputRepository.MAX_PAGE_SIZE, page.items.size)
        }

    @Test
    fun `countByService should return correct count`() =
        runBlocking {
            repeat(4) { CapturedInputRepository.create(createTestInput(serviceId = testServiceId)) }
            repeat(2) { CapturedInputRepository.create(createTestInput(serviceId = otherServiceId)) }

            val count = CapturedInputRepository.countByService(testServiceId)

            assertEquals(4L, count)
        }

    @Test
    fun `countByService should return zero when no inputs exist`() =
        runBlocking {
            val count = CapturedInputRepository.countByService(testServiceId)

            assertEquals(0L, count)
        }

    @Test
    fun `deleteByService should remove all inputs for that service scoped to org`() =
        runBlocking {
            repeat(
                3,
            ) { CapturedInputRepository.create(createTestInput(serviceId = testServiceId, organizationId = testOrgId)) }
            repeat(
                2,
            ) {
                CapturedInputRepository.create(
                    createTestInput(serviceId = otherServiceId, organizationId = testOrgId),
                )
            }

            val deleted = CapturedInputRepository.deleteByService(serviceId = testServiceId, organizationId = testOrgId)

            assertEquals(3L, deleted)
            assertEquals(0L, CapturedInputRepository.countByService(testServiceId))
            assertEquals(2L, CapturedInputRepository.countByService(otherServiceId))
        }

    @Test
    fun `deleteByService should not delete inputs belonging to a different org`() =
        runBlocking {
            repeat(
                3,
            ) { CapturedInputRepository.create(createTestInput(serviceId = testServiceId, organizationId = testOrgId)) }
            // Same serviceId but a different org — should NOT be deleted
            repeat(
                2,
            ) {
                CapturedInputRepository.create(
                    createTestInput(serviceId = testServiceId, organizationId = otherOrgId),
                )
            }

            val deleted = CapturedInputRepository.deleteByService(serviceId = testServiceId, organizationId = testOrgId)

            assertEquals(3L, deleted)
            // The other org's rows are still there
            assertEquals(2L, CapturedInputRepository.countByService(testServiceId))
        }

    @Test
    fun `deleteByService should return zero when no inputs exist`() =
        runBlocking {
            val deleted = CapturedInputRepository.deleteByService(serviceId = testServiceId, organizationId = testOrgId)

            assertEquals(0L, deleted)
        }
}
