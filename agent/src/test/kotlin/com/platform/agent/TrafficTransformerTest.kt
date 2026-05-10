package com.platform.agent

import com.platform.agent.models.KubesharkContent
import com.platform.agent.models.KubesharkEndpoint
import com.platform.agent.models.KubesharkEntry
import com.platform.agent.models.KubesharkPod
import com.platform.agent.models.KubesharkPodMetadata
import com.platform.agent.models.KubesharkPostData
import com.platform.agent.models.KubesharkProtocol
import com.platform.agent.models.KubesharkRequest
import com.platform.agent.models.KubesharkResponse
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrafficTransformerTest {
    private val targetServices =
        mapOf(
            "order-service" to "svc-123",
            "api-gateway" to "svc-456",
        )

    private fun transformer(
        services: Map<String, String> = targetServices,
        samplingRate: Double = 1.0,
        random: Random = Random.Default,
    ): TrafficTransformer {
        val config =
            DynamicConfig(
                targetServices = services,
                samplingRate = samplingRate,
            )
        return TrafficTransformer(MutableStateFlow(config), random)
    }

    private fun httpEntry(
        id: String = "entry-1",
        dstName: String? = "order-service-abc-xyz",
        appLabel: String? = "order-service",
        method: String? = "GET",
        url: String? = "/api/orders",
        status: Int? = 200,
        timestamp: Long = 1000L,
        reqHeaders: Map<String, String>? = null,
        reqBody: KubesharkPostData? = null,
        respHeaders: Map<String, String>? = null,
        respContent: KubesharkContent? = null,
        srcIp: String? = "10.0.0.1",
        dstIp: String? = "10.0.0.2",
    ) = KubesharkEntry(
        id = id,
        timestamp = timestamp,
        protocol = KubesharkProtocol(name = "http"),
        src = KubesharkEndpoint(ip = srcIp),
        dst =
            KubesharkEndpoint(
                name = dstName,
                ip = dstIp,
                pod =
                    appLabel?.let {
                        KubesharkPod(
                            metadata = KubesharkPodMetadata(labels = mapOf("app" to it)),
                        )
                    },
            ),
        request =
            KubesharkRequest(
                method = method,
                url = url,
                headers = reqHeaders,
                postData = reqBody,
            ),
        response =
            KubesharkResponse(
                status = status,
                headers = respHeaders,
                content = respContent,
            ),
    )

    @Test
    fun `transforms valid HTTP entry to CapturedInputRequest`() {
        val result =
            transformer().transform(
                listOf(
                    httpEntry(
                        reqHeaders = mapOf("Content-Type" to "application/json"),
                        respHeaders = mapOf("X-Request-Id" to "abc"),
                        respContent = KubesharkContent(text = """{"id": 1}"""),
                    ),
                ),
            )

        assertEquals(1, result.size)
        val captured = result[0]
        assertEquals("svc-123", captured.serviceId)
        assertEquals("HTTP", captured.inputType)
        assertEquals("GET", captured.method)
        assertEquals("/api/orders", captured.url)
        assertEquals(200, captured.responseStatus)
        assertEquals(mapOf("Content-Type" to "application/json"), captured.requestHeaders)
        assertEquals(mapOf("X-Request-Id" to "abc"), captured.responseHeaders)
        assertEquals("""{"id": 1}""", captured.responseBody)
        assertEquals("10.0.0.1", captured.sourceIp)
        assertEquals("10.0.0.2", captured.destinationIp)
        assertEquals("1970-01-01T00:00:01Z", captured.capturedAt)
    }

    @Test
    fun `captures request body from postData text`() {
        val result =
            transformer().transform(
                listOf(
                    httpEntry(
                        method = "POST",
                        reqBody =
                            KubesharkPostData(
                                text = """{"total": 222.21}""",
                                mimeType = "application/json",
                            ),
                    ),
                ),
            )

        assertEquals(1, result.size)
        assertEquals("""{"total": 222.21}""", result[0].requestBody)
    }

    @Test
    fun `request body is null when postData is absent`() {
        val result = transformer().transform(listOf(httpEntry()))

        assertEquals(1, result.size)
        assertNull(result[0].requestBody)
    }

    @Test
    fun `decodes base64-encoded response body`() {
        val plaintext = """{"id": 1, "status": "pending"}"""
        val base64 = Base64.getEncoder().encodeToString(plaintext.toByteArray())

        val result =
            transformer().transform(
                listOf(
                    httpEntry(
                        respContent =
                            KubesharkContent(
                                text = base64,
                                encoding = "base64",
                                mimeType = "application/json",
                            ),
                    ),
                ),
            )

        assertEquals(1, result.size)
        assertEquals(plaintext, result[0].responseBody)
    }

    @Test
    fun `passes through plaintext response body when no encoding set`() {
        val result =
            transformer().transform(
                listOf(
                    httpEntry(
                        respContent = KubesharkContent(text = "hello world"),
                    ),
                ),
            )

        assertEquals("hello world", result[0].responseBody)
    }

    @Test
    fun `response body is null when content is absent`() {
        val result = transformer().transform(listOf(httpEntry()))

        assertEquals(1, result.size)
        assertNull(result[0].responseBody)
    }

    @Test
    fun `drops response body when base64 is malformed but keeps entry`() {
        val result =
            transformer().transform(
                listOf(
                    httpEntry(
                        respContent =
                            KubesharkContent(
                                text = "!!! not valid base64 !!!",
                                encoding = "base64",
                            ),
                    ),
                ),
            )

        assertEquals(1, result.size, "entry should still be captured")
        assertNull(result[0].responseBody, "body should be dropped on decode failure")
    }

    @Test
    fun `populates latencyMs from elapsedTime`() {
        val entry =
            httpEntry().copy(elapsedTime = 42L)

        val result = transformer().transform(listOf(entry))

        assertEquals(1, result.size)
        assertEquals(42L, result[0].latencyMs)
    }

    @Test
    fun `latencyMs is null when elapsedTime is absent`() {
        val result = transformer().transform(listOf(httpEntry()))

        assertEquals(1, result.size)
        assertNull(result[0].latencyMs)
    }

    @Test
    fun `filters out non-HTTP entries`() {
        val grpcEntry =
            KubesharkEntry(
                id = "grpc-1",
                timestamp = 1000L,
                protocol = KubesharkProtocol(name = "grpc"),
                dst = KubesharkEndpoint(name = "order-service"),
                request = KubesharkRequest(method = "GetOrder", url = "/orders.OrderService/GetOrder"),
                response = KubesharkResponse(status = 0),
            )

        val result = transformer().transform(listOf(grpcEntry))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `filters out entries with no app label`() {
        val result = transformer().transform(listOf(httpEntry(appLabel = null)))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `filters out entries for non-target services`() {
        val result =
            transformer().transform(
                listOf(httpEntry(appLabel = "unknown-service")),
            )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `filters out entries missing method`() {
        val result = transformer().transform(listOf(httpEntry(method = null)))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `filters out entries missing url`() {
        val result = transformer().transform(listOf(httpEntry(url = null)))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `filters out entries missing status`() {
        val result = transformer().transform(listOf(httpEntry(status = null)))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `maps correct service ID for each target service`() {
        val entries =
            listOf(
                httpEntry(id = "e1", appLabel = "order-service"),
                httpEntry(id = "e2", appLabel = "api-gateway"),
            )

        val result = transformer().transform(entries)

        assertEquals(2, result.size)
        assertEquals("svc-123", result[0].serviceId)
        assertEquals("svc-456", result[1].serviceId)
    }

    @Test
    fun `empty target services filters everything`() {
        val result =
            transformer(services = emptyMap()).transform(
                listOf(httpEntry()),
            )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `handles empty input list`() {
        val result = transformer().transform(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `reads config from StateFlow on each call`() {
        val configFlow =
            MutableStateFlow(
                DynamicConfig(targetServices = targetServices, samplingRate = 1.0),
            )
        val transformer = TrafficTransformer(configFlow)

        val before = transformer.transform(listOf(httpEntry()))
        assertEquals(1, before.size)

        configFlow.value = DynamicConfig(targetServices = emptyMap())

        val after = transformer.transform(listOf(httpEntry()))
        assertTrue(after.isEmpty())
    }

    @Test
    fun `sampling at 50 percent accepts approximately half the entries`() {
        val seededRandom = Random(seed = 42)
        val t = transformer(samplingRate = 0.5, random = seededRandom)

        val entries = (1..1000).map { i -> httpEntry(id = "e-$i", timestamp = i.toLong()) }
        val result = t.transform(entries)

        // With a seeded Random, result is deterministic. With 1000 entries at 0.5,
        // expect ~500. Allow a wide margin to avoid flakiness if seed behavior
        // differs across JVM versions, but catch always-accept/always-reject bugs.
        assertTrue(result.size in 400..600, "Expected ~500 but got ${result.size}")
    }

    @Test
    fun `sampling at zero rejects all entries`() {
        val t = transformer(samplingRate = 0.0)
        val entries = (1..100).map { i -> httpEntry(id = "e-$i", timestamp = i.toLong()) }
        assertTrue(t.transform(entries).isEmpty())
    }
}
