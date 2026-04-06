package com.platform.agent

import com.platform.agent.models.KubesharkContent
import com.platform.agent.models.KubesharkEndpoint
import com.platform.agent.models.KubesharkEntry
import com.platform.agent.models.KubesharkHeader
import com.platform.agent.models.KubesharkProtocol
import com.platform.agent.models.KubesharkRequest
import com.platform.agent.models.KubesharkResponse
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrafficTransformerTest {
    private val targetServices =
        mapOf(
            "order-service" to "svc-123",
            "api-gateway" to "svc-456",
        )

    private fun transformer(services: Map<String, String> = targetServices): TrafficTransformer {
        val config =
            DynamicConfig(
                targetServices = services,
                samplingRate = 1.0,
            )
        return TrafficTransformer(AtomicReference(config))
    }

    private fun httpEntry(
        id: String = "entry-1",
        dstName: String? = "order-service",
        method: String? = "GET",
        url: String? = "/api/orders",
        status: Int? = 200,
        timestamp: Long = 1000L,
        reqHeaders: List<KubesharkHeader>? = null,
        respHeaders: List<KubesharkHeader>? = null,
        respBody: String? = null,
        srcIp: String? = "10.0.0.1",
        dstIp: String? = "10.0.0.2",
    ) = KubesharkEntry(
        id = id,
        timestamp = timestamp,
        protocol = KubesharkProtocol(name = "http"),
        src = KubesharkEndpoint(ip = srcIp),
        dst = KubesharkEndpoint(name = dstName, ip = dstIp),
        request = KubesharkRequest(method = method, url = url, headers = reqHeaders),
        response =
            KubesharkResponse(
                status = status,
                headers = respHeaders,
                content = respBody?.let { KubesharkContent(text = it) },
            ),
    )

    @Test
    fun `transforms valid HTTP entry to CapturedInputRequest`() {
        val result =
            transformer().transform(
                listOf(
                    httpEntry(
                        reqHeaders =
                            listOf(
                                KubesharkHeader("Content-Type", "application/json"),
                            ),
                        respHeaders =
                            listOf(
                                KubesharkHeader("X-Request-Id", "abc"),
                            ),
                        respBody = """{"id": 1}""",
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
    fun `filters out entries with no dst name`() {
        val result = transformer().transform(listOf(httpEntry(dstName = null)))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `filters out entries for non-target services`() {
        val result =
            transformer().transform(
                listOf(httpEntry(dstName = "unknown-service")),
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
                httpEntry(id = "e1", dstName = "order-service"),
                httpEntry(id = "e2", dstName = "api-gateway"),
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
    fun `reads config from AtomicReference on each call`() {
        val configRef =
            AtomicReference(
                DynamicConfig(targetServices = targetServices, samplingRate = 1.0),
            )
        val transformer = TrafficTransformer(configRef)

        val before = transformer.transform(listOf(httpEntry()))
        assertEquals(1, before.size)

        configRef.set(DynamicConfig(targetServices = emptyMap()))

        val after = transformer.transform(listOf(httpEntry()))
        assertTrue(after.isEmpty())
    }
}
