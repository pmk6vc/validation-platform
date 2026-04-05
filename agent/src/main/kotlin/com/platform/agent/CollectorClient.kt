package com.platform.agent

import com.platform.agent.models.BatchCapturedInputRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import org.slf4j.LoggerFactory

class CollectorClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val authToken: String,
) {
    private val logger = LoggerFactory.getLogger(CollectorClient::class.java)

    /**
     * Post a batch of captured inputs to the collector API.
     *
     * @return true if the batch was accepted, false otherwise
     */
    suspend fun sendBatch(batch: BatchCapturedInputRequest): Boolean {
        if (batch.items.isEmpty()) return true

        return try {
            val response =
                httpClient.post("$baseUrl/api/captured-inputs") {
                    contentType(ContentType.Application.Json)
                    bearerAuth(authToken)
                    setBody(batch)
                }

            if (response.status.isSuccess()) {
                logger.debug("Sent batch of {} captured inputs", batch.items.size)
                true
            } else {
                logger.warn(
                    "Collector rejected batch of {}: {} {}",
                    batch.items.size,
                    response.status,
                    response.bodyAsText(),
                )
                false
            }
        } catch (e: Exception) {
            logger.error("Failed to send batch to collector", e)
            false
        }
    }
}
