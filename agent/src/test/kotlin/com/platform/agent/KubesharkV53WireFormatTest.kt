package com.platform.agent

import com.platform.agent.models.KubesharkEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Locks in the real Kubeshark v53.2 wire format. The HAR-spec docs describe
 * `request.headers` as a list of `{name, value}` objects; v53 actually emits
 * a JSON object (`{"Accept":"...",...}`). Treating it as a list throws on
 * `$.request.headers` and silently drops every captured frame.
 *
 * Fixture is a recorded payload from a sandbox sniffer.
 */
class KubesharkV53WireFormatTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val raw =
        requireNotNull(javaClass.getResourceAsStream("/com/platform/agent/kubeshark-v53-http-entry.json")) {
            "fixture not found"
        }.bufferedReader().readText()

    @Test
    fun `parses headers as JSON object, not HAR-style array`() {
        val entry = json.decodeFromString<KubesharkEntry>(raw)

        val expectedReq = mapOf("Accept" to "*/*", "Host" to "api-gateway:8080", "User-Agent" to "curl/8.10.1")
        val expectedResp = mapOf("Content-Type" to "application/json", "X-Request-Id" to "abc-123")
        assertEquals(expectedReq, entry.request?.headers)
        assertEquals(expectedResp, entry.response?.headers)
    }

    @Test
    fun `transformer produces a CapturedInputRequest from a v53 wire payload`() {
        val entry = json.decodeFromString<KubesharkEntry>(raw)

        val transformer =
            TrafficTransformer(
                MutableStateFlow(DynamicConfig(targetServices = mapOf("api-gateway" to "svc-1"))),
            )
        val out = transformer.transform(listOf(entry))

        assertEquals(1, out.size)
        val captured = out.single()
        assertEquals("svc-1", captured.serviceId)
        assertEquals("GET", captured.method)
        assertEquals(200, captured.responseStatus)
        assertNotNull(captured.requestHeaders)
        assertEquals("*/*", captured.requestHeaders!!["Accept"])
        // base64-encoded {"orders": []}
        assertEquals("""{"orders": []}""", captured.responseBody)
    }
}
