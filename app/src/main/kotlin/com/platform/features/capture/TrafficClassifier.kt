package com.platform.features.capture

import com.platform.models.capture.TrafficClassification
import kotlinx.serialization.Serializable

/**
 * An override that maps a specific "METHOD URL" pattern to a classification.
 *
 * For example, `EndpointOverride("POST /api/search", TrafficClassification.READ)` treats
 * POST /api/search as a read operation, overriding the default POST → WRITE rule.
 * Matching is exact on the combined "METHOD URL" string.
 */
@Serializable
data class EndpointOverride(
    val pattern: String,
    val classification: TrafficClassification,
)

/**
 * Classifies HTTP traffic as READ or WRITE based on the HTTP method.
 *
 * The default rules follow HTTP semantics:
 * - GET, HEAD, OPTIONS → READ (safe, idempotent)
 * - POST, PUT, PATCH, DELETE → WRITE (may mutate state)
 *
 * Overrides allow teams to correct classifications for endpoints that don't follow
 * standard conventions (e.g., a POST that is actually a query).
 */
object TrafficClassifier {
    /**
     * Classifies an HTTP method, applying any provided overrides first.
     *
     * @param method HTTP method (case-insensitive)
     * @param url Optional URL path; combined with method for override matching
     * @param overrides List of endpoint-specific classification overrides
     * @return The classification for this method/URL combination
     */
    fun classify(
        method: String,
        url: String? = null,
        overrides: List<EndpointOverride> = emptyList(),
    ): TrafficClassification {
        val normalizedMethod = method.uppercase()
        val overrideKey = if (url != null) "$normalizedMethod $url" else normalizedMethod

        val override = overrides.firstOrNull { it.pattern == overrideKey }
        if (override != null) return override.classification

        return when (normalizedMethod) {
            "GET", "HEAD", "OPTIONS" -> TrafficClassification.READ
            "POST", "PUT", "PATCH", "DELETE" -> TrafficClassification.WRITE
            else -> TrafficClassification.UNKNOWN
        }
    }
}
