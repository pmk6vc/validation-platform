package com.platform.agent

import com.platform.agent.models.KubesharkEntry
import com.platform.agent.models.KubesharkResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class KubesharkClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) {
    private val logger = LoggerFactory.getLogger(KubesharkClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetch HTTP API calls from Kubeshark.
     *
     * @param startMs Unix timestamp in milliseconds — only return entries after this time
     * @param limit Maximum number of entries to return
     * @return List of Kubeshark entries (unfiltered — caller handles dedup and service filtering)
     */
    suspend fun listHttpCalls(
        startMs: Long? = null,
        limit: Int = 100,
    ): List<KubesharkEntry> {
        val response =
            httpClient.get("$baseUrl/api/entries") {
                parameter("q", "http")
                parameter("limit", limit)
                parameter("format", "full")
                startMs?.let { parameter("start", it) }
            }

        if (!response.status.isSuccess()) {
            logger.warn("Kubeshark API returned {}: {}", response.status, response.bodyAsText())
            return emptyList()
        }

        val body = response.bodyAsText()
        return try {
            json.decodeFromString<KubesharkResponse>(body).calls
        } catch (e: Exception) {
            logger.error("Failed to parse Kubeshark response", e)
            emptyList()
        }
    }
}
