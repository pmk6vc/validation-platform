package com.platform.collector.database

import com.platform.collector.models.CapturedInput
import com.platform.collector.models.InputType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapturedInputRepositoryTest : CollectorDatabaseTestBase() {
    private val repository = CapturedInputRepository()

    private lateinit var testServiceId: String
    private lateinit var otherServiceId: String

    @BeforeEach
    fun setupServiceFixtures() {
        runBlocking {
            val org = AppApiTestHelper.createOrganization("Test Organization")

            val service =
                AppApiTestHelper.createService(
                    organizationId = org.id,
                    name = "order-service",
                )
            testServiceId = service.id

            val other =
                AppApiTestHelper.createService(
                    organizationId = org.id,
                    name = "payment-service",
                )
            otherServiceId = other.id
        }
    }

    private fun createTestInput(
        id: String = UUID.randomUUID().toString(),
        serviceId: String = testServiceId,
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
        id = id,
        serviceId = serviceId,
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

            val created = repository.create(input)

            assertEquals(input.id, created.id)
            assertEquals(testServiceId, created.serviceId)
            assertEquals(InputType.HTTP, created.inputType)
        }

    @Test
    fun `findById should return captured input when exists`() =
        runBlocking {
            val input =
                createTestInput(
                    method = "POST",
                    url = "/api/orders",
                    requestBody = """{"item":"book"}""",
                    responseStatus = 201,
                    latencyMs = 125L,
                )
            repository.create(input)

            val found = repository.findById(input.id)

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
            val found = repository.findById(UUID.randomUUID().toString())

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
            repository.create(input)

            val found = repository.findById(input.id)

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
            repository.create(input)

            val found = repository.findById(input.id)

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

            repository.createBatch(inputs)

            val page = repository.find(serviceId = testServiceId)
            assertEquals(5, page.items.size)
        }

    @Test
    fun `createBatch should return the same inputs`() =
        runBlocking {
            val inputs = (1..3).map { createTestInput() }

            val result = repository.createBatch(inputs)

            assertEquals(inputs.map { it.id }.toSet(), result.map { it.id }.toSet())
        }

    @Test
    fun `find with serviceId should return only inputs for that service`() =
        runBlocking {
            val input1 = createTestInput(serviceId = testServiceId)
            val input2 = createTestInput(serviceId = testServiceId)
            val input3 = createTestInput(serviceId = otherServiceId)
            repository.create(input1)
            repository.create(input2)
            repository.create(input3)

            val page = repository.find(serviceId = testServiceId)

            assertEquals(2, page.items.size)
            assertTrue(page.items.all { it.serviceId == testServiceId })
        }

    @Test
    fun `find with inputType should return only inputs of that type`() =
        runBlocking {
            val httpInput1 = createTestInput(inputType = InputType.HTTP)
            val httpInput2 = createTestInput(inputType = InputType.HTTP)
            repository.create(httpInput1)
            repository.create(httpInput2)

            val page = repository.find(inputType = InputType.HTTP)

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
            repository.create(match)
            repository.create(wrongService)

            val page =
                repository.find(
                    serviceId = testServiceId,
                    inputType = InputType.HTTP,
                )

            assertEquals(1, page.items.size)
            assertEquals(match.id, page.items[0].id)
        }

    @Test
    fun `find with limit should return at most limit items`() =
        runBlocking {
            repeat(5) { repository.create(createTestInput()) }

            val page = repository.find(limit = 3)

            assertEquals(3, page.items.size)
        }

    @Test
    fun `find should return nextCursor when more items exist`() =
        runBlocking {
            repeat(5) { repository.create(createTestInput()) }

            val page = repository.find(limit = 3)

            assertEquals(3, page.items.size)
            assertNotNull(page.nextCursor)
        }

    @Test
    fun `find should return null nextCursor when no more items`() =
        runBlocking {
            repeat(3) { repository.create(createTestInput()) }

            val page = repository.find(limit = 5)

            assertEquals(3, page.items.size)
            assertNull(page.nextCursor)
        }

    @Test
    fun `find with cursor should return items after cursor`() =
        runBlocking {
            repeat(5) { repository.create(createTestInput()) }

            val firstPage = repository.find(limit = 2)
            assertEquals(2, firstPage.items.size)
            assertNotNull(firstPage.nextCursor)

            val secondPage = repository.find(limit = 2, cursor = firstPage.nextCursor)
            assertEquals(2, secondPage.items.size)
            assertNotNull(secondPage.nextCursor)

            val firstPageIds = firstPage.items.map { it.id }.toSet()
            val secondPageIds = secondPage.items.map { it.id }.toSet()
            assertTrue(firstPageIds.intersect(secondPageIds).isEmpty())

            val thirdPage = repository.find(limit = 2, cursor = secondPage.nextCursor)
            assertEquals(1, thirdPage.items.size)
            assertNull(thirdPage.nextCursor)
        }

    @Test
    fun `find should enforce max page size`() =
        runBlocking {
            repeat(150) { repository.create(createTestInput()) }

            val page = repository.find(limit = 200)

            assertEquals(CapturedInputRepository.MAX_PAGE_SIZE, page.items.size)
        }

    @Test
    fun `countByService should return correct count`() =
        runBlocking {
            repeat(4) { repository.create(createTestInput(serviceId = testServiceId)) }
            repeat(2) { repository.create(createTestInput(serviceId = otherServiceId)) }

            val count = repository.countByService(testServiceId)

            assertEquals(4L, count)
        }

    @Test
    fun `countByService should return zero when no inputs exist`() =
        runBlocking {
            val count = repository.countByService(testServiceId)

            assertEquals(0L, count)
        }

    @Test
    fun `deleteByService should remove all inputs for that service`() =
        runBlocking {
            repeat(3) { repository.create(createTestInput(serviceId = testServiceId)) }
            repeat(2) { repository.create(createTestInput(serviceId = otherServiceId)) }

            val deleted = repository.deleteByService(testServiceId)

            assertEquals(3L, deleted)
            assertEquals(0L, repository.countByService(testServiceId))
            assertEquals(2L, repository.countByService(otherServiceId))
        }

    @Test
    fun `deleteByService should return zero when no inputs exist`() =
        runBlocking {
            val deleted = repository.deleteByService(testServiceId)

            assertEquals(0L, deleted)
        }
}
