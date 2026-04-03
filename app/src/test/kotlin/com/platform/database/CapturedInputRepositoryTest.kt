package com.platform.database

import com.platform.models.Organization
import com.platform.models.Provider
import com.platform.models.Service
import com.platform.models.capture.CapturedInput
import com.platform.models.capture.InputType
import com.platform.models.capture.TrafficClassification
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapturedInputRepositoryTest : DatabaseTestBase() {
    private lateinit var testOrg: Organization
    private lateinit var testService: Service
    private lateinit var otherService: Service

    @BeforeEach
    fun setupServiceFixtures() {
        runBlocking {
            testOrg =
                Organization(
                    id = UUID.randomUUID().toString(),
                    name = "Test Organization",
                    createdAt = Instant.now(),
                )
            OrganizationRepository.create(testOrg)

            testService =
                Service(
                    id = UUID.randomUUID().toString(),
                    organizationId = testOrg.id,
                    cluster = "prod",
                    namespace = "default",
                    name = "order-service",
                    provider = Provider.KUBERNETES,
                    discoveredAt = Instant.now(),
                    lastSeenAt = Instant.now(),
                )
            ServiceRepository.create(testService)

            otherService =
                Service(
                    id = UUID.randomUUID().toString(),
                    organizationId = testOrg.id,
                    cluster = "prod",
                    namespace = "default",
                    name = "payment-service",
                    provider = Provider.KUBERNETES,
                    discoveredAt = Instant.now(),
                    lastSeenAt = Instant.now(),
                )
            ServiceRepository.create(otherService)
        }
    }

    private fun createTestInput(
        id: String = UUID.randomUUID().toString(),
        serviceId: String = testService.id,
        inputType: InputType = InputType.HTTP,
        classification: TrafficClassification = TrafficClassification.READ,
        method: String? = "GET",
        url: String? = "/api/orders",
        requestHeaders: Map<String, String>? = null,
        requestBody: String? = null,
        responseStatus: Int? = 200,
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
        classification = classification,
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
            assertEquals(testService.id, created.serviceId)
            assertEquals(InputType.HTTP, created.inputType)
            assertEquals(TrafficClassification.READ, created.classification)
        }

    @Test
    fun `findById should return captured input when exists`() =
        runBlocking {
            val input =
                createTestInput(
                    method = "POST",
                    url = "/api/orders",
                    classification = TrafficClassification.WRITE,
                    requestBody = """{"item":"book"}""",
                    responseStatus = 201,
                    latencyMs = 125L,
                )
            CapturedInputRepository.create(input)

            val found = CapturedInputRepository.findById(input.id)

            assertNotNull(found)
            assertEquals(input.id, found.id)
            assertEquals("POST", found.method)
            assertEquals("/api/orders", found.url)
            assertEquals(TrafficClassification.WRITE, found.classification)
            assertEquals("""{"item":"book"}""", found.requestBody)
            assertEquals(201, found.responseStatus)
            assertEquals(125L, found.latencyMs)
        }

    @Test
    fun `findById should return null when not exists`() =
        runBlocking {
            val found = CapturedInputRepository.findById(UUID.randomUUID().toString())

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

            val found = CapturedInputRepository.findById(input.id)

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

            val found = CapturedInputRepository.findById(input.id)

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

            val page = CapturedInputRepository.find(serviceId = testService.id)
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
            val input1 = createTestInput(serviceId = testService.id)
            val input2 = createTestInput(serviceId = testService.id)
            val input3 = createTestInput(serviceId = otherService.id)
            CapturedInputRepository.create(input1)
            CapturedInputRepository.create(input2)
            CapturedInputRepository.create(input3)

            val page = CapturedInputRepository.find(serviceId = testService.id)

            assertEquals(2, page.items.size)
            assertTrue(page.items.all { it.serviceId == testService.id })
        }

    @Test
    fun `find with inputType should return only inputs of that type`() =
        runBlocking {
            val httpInput1 = createTestInput(inputType = InputType.HTTP)
            val httpInput2 = createTestInput(inputType = InputType.HTTP)
            val kafkaInput = createTestInput(inputType = InputType.KAFKA)
            CapturedInputRepository.create(httpInput1)
            CapturedInputRepository.create(httpInput2)
            CapturedInputRepository.create(kafkaInput)

            val page = CapturedInputRepository.find(inputType = InputType.HTTP)

            assertEquals(2, page.items.size)
            assertTrue(page.items.all { it.inputType == InputType.HTTP })
        }

    @Test
    fun `find with classification should return only inputs with that classification`() =
        runBlocking {
            val read1 = createTestInput(classification = TrafficClassification.READ)
            val read2 = createTestInput(classification = TrafficClassification.READ)
            val write = createTestInput(classification = TrafficClassification.WRITE)
            CapturedInputRepository.create(read1)
            CapturedInputRepository.create(read2)
            CapturedInputRepository.create(write)

            val page = CapturedInputRepository.find(classification = TrafficClassification.READ)

            assertEquals(2, page.items.size)
            assertTrue(page.items.all { it.classification == TrafficClassification.READ })
        }

    @Test
    fun `find with multiple filters should combine them with AND`() =
        runBlocking {
            val match =
                createTestInput(
                    serviceId = testService.id,
                    inputType = InputType.HTTP,
                    classification = TrafficClassification.WRITE,
                )
            val wrongService =
                createTestInput(
                    serviceId = otherService.id,
                    inputType = InputType.HTTP,
                    classification = TrafficClassification.WRITE,
                )
            val wrongClassification =
                createTestInput(
                    serviceId = testService.id,
                    inputType = InputType.HTTP,
                    classification = TrafficClassification.READ,
                )
            CapturedInputRepository.create(match)
            CapturedInputRepository.create(wrongService)
            CapturedInputRepository.create(wrongClassification)

            val page =
                CapturedInputRepository.find(
                    serviceId = testService.id,
                    classification = TrafficClassification.WRITE,
                )

            assertEquals(1, page.items.size)
            assertEquals(match.id, page.items[0].id)
        }

    @Test
    fun `find with limit should return at most limit items`() =
        runBlocking {
            repeat(5) { CapturedInputRepository.create(createTestInput()) }

            val page = CapturedInputRepository.find(limit = 3)

            assertEquals(3, page.items.size)
        }

    @Test
    fun `find should return nextCursor when more items exist`() =
        runBlocking {
            repeat(5) { CapturedInputRepository.create(createTestInput()) }

            val page = CapturedInputRepository.find(limit = 3)

            assertEquals(3, page.items.size)
            assertNotNull(page.nextCursor)
        }

    @Test
    fun `find should return null nextCursor when no more items`() =
        runBlocking {
            repeat(3) { CapturedInputRepository.create(createTestInput()) }

            val page = CapturedInputRepository.find(limit = 5)

            assertEquals(3, page.items.size)
            assertNull(page.nextCursor)
        }

    @Test
    fun `find with cursor should return items after cursor`() =
        runBlocking {
            repeat(5) { CapturedInputRepository.create(createTestInput()) }

            val firstPage = CapturedInputRepository.find(limit = 2)
            assertEquals(2, firstPage.items.size)
            assertNotNull(firstPage.nextCursor)

            val secondPage = CapturedInputRepository.find(limit = 2, cursor = firstPage.nextCursor)
            assertEquals(2, secondPage.items.size)
            assertNotNull(secondPage.nextCursor)

            val firstPageIds = firstPage.items.map { it.id }.toSet()
            val secondPageIds = secondPage.items.map { it.id }.toSet()
            assertTrue(firstPageIds.intersect(secondPageIds).isEmpty())

            val thirdPage = CapturedInputRepository.find(limit = 2, cursor = secondPage.nextCursor)
            assertEquals(1, thirdPage.items.size)
            assertNull(thirdPage.nextCursor)
        }

    @Test
    fun `find should enforce max page size`() =
        runBlocking {
            repeat(150) { CapturedInputRepository.create(createTestInput()) }

            val page = CapturedInputRepository.find(limit = 200)

            assertEquals(CapturedInputRepository.MAX_PAGE_SIZE, page.items.size)
        }

    @Test
    fun `countByService should return correct count`() =
        runBlocking {
            repeat(4) { CapturedInputRepository.create(createTestInput(serviceId = testService.id)) }
            repeat(2) { CapturedInputRepository.create(createTestInput(serviceId = otherService.id)) }

            val count = CapturedInputRepository.countByService(testService.id)

            assertEquals(4L, count)
        }

    @Test
    fun `countByService should return zero when no inputs exist`() =
        runBlocking {
            val count = CapturedInputRepository.countByService(testService.id)

            assertEquals(0L, count)
        }

    @Test
    fun `deleteByService should remove all inputs for that service`() =
        runBlocking {
            repeat(3) { CapturedInputRepository.create(createTestInput(serviceId = testService.id)) }
            repeat(2) { CapturedInputRepository.create(createTestInput(serviceId = otherService.id)) }

            val deleted = CapturedInputRepository.deleteByService(testService.id)

            assertEquals(3L, deleted)
            assertEquals(0L, CapturedInputRepository.countByService(testService.id))
            // Other service inputs should be untouched
            assertEquals(2L, CapturedInputRepository.countByService(otherService.id))
        }

    @Test
    fun `deleteByService should return zero when no inputs exist`() =
        runBlocking {
            val deleted = CapturedInputRepository.deleteByService(testService.id)

            assertEquals(0L, deleted)
        }
}
