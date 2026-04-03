package com.platform.features.capture

import com.platform.models.capture.TrafficClassification
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TrafficClassifierTest {
    @Test
    fun `GET should be classified as READ`() {
        val result = TrafficClassifier.classify("GET")

        assertEquals(TrafficClassification.READ, result)
    }

    @Test
    fun `HEAD should be classified as READ`() {
        val result = TrafficClassifier.classify("HEAD")

        assertEquals(TrafficClassification.READ, result)
    }

    @Test
    fun `OPTIONS should be classified as READ`() {
        val result = TrafficClassifier.classify("OPTIONS")

        assertEquals(TrafficClassification.READ, result)
    }

    @Test
    fun `POST should be classified as WRITE`() {
        val result = TrafficClassifier.classify("POST")

        assertEquals(TrafficClassification.WRITE, result)
    }

    @Test
    fun `PUT should be classified as WRITE`() {
        val result = TrafficClassifier.classify("PUT")

        assertEquals(TrafficClassification.WRITE, result)
    }

    @Test
    fun `PATCH should be classified as WRITE`() {
        val result = TrafficClassifier.classify("PATCH")

        assertEquals(TrafficClassification.WRITE, result)
    }

    @Test
    fun `DELETE should be classified as WRITE`() {
        val result = TrafficClassifier.classify("DELETE")

        assertEquals(TrafficClassification.WRITE, result)
    }

    @Test
    fun `unknown method should be classified as UNKNOWN`() {
        val result = TrafficClassifier.classify("PROPFIND")

        assertEquals(TrafficClassification.UNKNOWN, result)
    }

    @Test
    fun `method matching should be case-insensitive`() {
        assertEquals(TrafficClassification.READ, TrafficClassifier.classify("get"))
        assertEquals(TrafficClassification.READ, TrafficClassifier.classify("Get"))
        assertEquals(TrafficClassification.WRITE, TrafficClassifier.classify("post"))
        assertEquals(TrafficClassification.WRITE, TrafficClassifier.classify("Post"))
    }

    @Test
    fun `override should reclassify POST as READ for search endpoint`() {
        // Given — POST /api/search is a query endpoint, not a mutation
        val overrides =
            listOf(
                EndpointOverride("POST /api/search", TrafficClassification.READ),
            )

        val result = TrafficClassifier.classify("POST", "/api/search", overrides)

        assertEquals(TrafficClassification.READ, result)
    }

    @Test
    fun `override should reclassify GET as WRITE for side-effecting endpoint`() {
        // Given — GET /api/trigger-job has side effects despite using GET
        val overrides =
            listOf(
                EndpointOverride("GET /api/trigger-job", TrafficClassification.WRITE),
            )

        val result = TrafficClassifier.classify("GET", "/api/trigger-job", overrides)

        assertEquals(TrafficClassification.WRITE, result)
    }

    @Test
    fun `override should only apply to the matched URL not other URLs`() {
        // Given — only /api/search is overridden
        val overrides =
            listOf(
                EndpointOverride("POST /api/search", TrafficClassification.READ),
            )

        // POST to a different URL should still be WRITE
        val result = TrafficClassifier.classify("POST", "/api/orders", overrides)

        assertEquals(TrafficClassification.WRITE, result)
    }

    @Test
    fun `override without URL should not match when URL is provided`() {
        // Given — override is defined without a URL component (method-only pattern)
        val overrides =
            listOf(
                EndpointOverride("POST", TrafficClassification.READ),
            )

        // POST with a URL should NOT match the method-only pattern because
        // the key becomes "POST /api/orders", not "POST"
        val result = TrafficClassifier.classify("POST", "/api/orders", overrides)

        assertEquals(TrafficClassification.WRITE, result)
    }

    @Test
    fun `first matching override wins when multiple overrides exist`() {
        val overrides =
            listOf(
                EndpointOverride("POST /api/search", TrafficClassification.READ),
                EndpointOverride("POST /api/search", TrafficClassification.UNKNOWN),
            )

        val result = TrafficClassifier.classify("POST", "/api/search", overrides)

        assertEquals(TrafficClassification.READ, result)
    }

    @Test
    fun `classify without overrides parameter uses defaults`() {
        // Verify the default empty list works naturally
        assertEquals(TrafficClassification.READ, TrafficClassifier.classify("GET", "/api/orders"))
        assertEquals(TrafficClassification.WRITE, TrafficClassifier.classify("DELETE", "/api/orders/1"))
    }
}
