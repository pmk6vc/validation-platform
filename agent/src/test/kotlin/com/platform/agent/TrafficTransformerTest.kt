package com.platform.agent

import com.platform.agent.models.KubesharkEndpoint
import com.platform.agent.models.KubesharkEntry
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

    private fun transformer(
        services: Map<String, String> = targetServices,
        samplingRate: Double = 1.0,
        random: () -> Double = { 0.0 },
    ): TrafficTransformer {
        val config =
            DynamicConfig(
                targetServices = services,
                samplingRate = samplingRate,
            )
        return TrafficTransformer(AtomicReference(config), random)
    }

    private fun httpEntry(
        id: String = "entry-1",
        dstSvc: String? = "order-service",
        method: String? = "GET",
        url: String? = "/api/orders",
        status: Int? = 200,
        ts: Long = 1000L,
        reqHeaders: Map<String, String>? = null,
        reqBody: String? = null,
        respHeaders: Map<String, String>? = null,
        respBody: String? = null,
        srcIp: String? = "10.0.0.1",
        dstIp: String? = "10.0.0.2",
    ) = KubesharkEntry(
        id = id,
        ts = ts,
        proto = "http",
        src = KubesharkEndpoint(ip = srcIp),
        dst = KubesharkEndpoint(svc = dstSvc, ip = dstIp),
        method = method,
        url = url,
        status = status,
        reqHeaders = reqHeaders,
        reqBody = reqBody,
        respHeaders = respHeaders,
        respBody = respBody,
    )

    @Test
    fun `transforms valid HTTP entry to CapturedInputRequest`() {
        val result =
            transformer().transform(
                listOf(
                    httpEntry(
                        reqHeaders = mapOf("Content-Type" to "application/json"),
                        reqBody = """{"item": "widget"}""",
                        respHeaders = mapOf("X-Request-Id" to "abc"),
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
        assertEquals("""{"item": "widget"}""", captured.requestBody)
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
                ts = 1000L,
                proto = "grpc",
                dst = KubesharkEndpoint(svc = "order-service"),
                method = "GetOrder",
                url = "/orders.OrderService/GetOrder",
                status = 0,
            )

        val result = transformer().transform(listOf(grpcEntry))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `filters out entries with no dst svc`() {
        val result = transformer().transform(listOf(httpEntry(dstSvc = null)))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `filters out entries for non-target services`() {
        val result =
            transformer().transform(
                listOf(httpEntry(dstSvc = "unknown-service")),
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
                httpEntry(id = "e1", dstSvc = "order-service"),
                httpEntry(id = "e2", dstSvc = "api-gateway"),
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

    // --- Sampling tests (deterministic via injected random) ---

    @Test
    fun `sampling includes entry when random is below rate`() {
        val result =
            transformer(samplingRate = 0.3, random = { 0.29 })
                .transform(listOf(httpEntry()))
        assertEquals(1, result.size)
    }

    @Test
    fun `sampling excludes entry when random equals rate`() {
        val result =
            transformer(samplingRate = 0.3, random = { 0.3 })
                .transform(listOf(httpEntry()))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `sampling excludes entry when random exceeds rate`() {
        val result =
            transformer(samplingRate = 0.3, random = { 0.5 })
                .transform(listOf(httpEntry()))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `sampling rate 1 always includes`() {
        val result =
            transformer(samplingRate = 1.0, random = { 0.999 })
                .transform(listOf(httpEntry()))
        assertEquals(1, result.size)
    }

    @Test
    fun `sampling rate 0 always excludes`() {
        val result =
            transformer(samplingRate = 0.0, random = { 0.0 })
                .transform(listOf(httpEntry()))
        assertTrue(result.isEmpty())
    }

    // --- AtomicReference hot-swap test ---

    @Test
    fun `reads config from AtomicReference on each call`() {
        val configRef =
            AtomicReference(
                DynamicConfig(targetServices = targetServices, samplingRate = 1.0),
            )
        val transformer = TrafficTransformer(configRef, random = { 0.0 })

        val before = transformer.transform(listOf(httpEntry()))
        assertEquals(1, before.size)

        configRef.set(DynamicConfig(targetServices = emptyMap()))

        val after = transformer.transform(listOf(httpEntry()))
        assertTrue(after.isEmpty())
    }
}
