package com.platform.agent

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class ConfigClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
) {
    private val logger = LoggerFactory.getLogger(ConfigClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetch dynamic config from the platform.
     *
     * The platform scopes the response based on the bearer token (once auth
     * is implemented). Until then, the endpoint returns all registered services.
     *
     * @return updated config, or null if the request failed
     */
    suspend fun fetchConfig(): DynamicConfig? {
        return try {
            val response =
                httpClient.get("$baseUrl/api/agent/config") {
                    bearerAuth(apiKey)
                }

            if (!response.status.isSuccess()) {
                logger.warn(
                    "Config endpoint returned {}: {}",
                    response.status,
                    response.bodyAsText(),
                )
                return null
            }

            json.decodeFromString<DynamicConfig>(response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to fetch config from platform", e)
            null
        }
    }
}
