package com.platform.collector.database

import com.platform.api.CreateOrganizationRequest
import com.platform.api.CreateServiceRequest
import com.platform.module
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

@Serializable
data class CreatedOrganization(
    val id: String,
    val name: String,
)

@Serializable
data class CreatedService(
    val id: String,
    val organizationId: String,
    val name: String,
)

private val lenientJson = Json { ignoreUnknownKeys = true }

object AppApiTestHelper {
    /**
     * Create an organization and a service in a single testApplication session,
     * avoiding the overhead of two separate Ktor startups per test.
     */
    suspend fun createOrganizationAndService(
        orgName: String,
        serviceName: String,
        cluster: String = "prod",
        namespace: String = "default",
    ): Pair<CreatedOrganization, CreatedService> =
        withAppClient { client ->
            val org = client.postOrganization(orgName)
            val svc = client.postService(org.id, serviceName, cluster, namespace)
            Pair(org, svc)
        }

    suspend fun createOrganization(name: String): CreatedOrganization =
        withAppClient { client -> client.postOrganization(name) }

    suspend fun createService(
        organizationId: String,
        name: String,
        cluster: String = "prod",
        namespace: String = "default",
    ): CreatedService = withAppClient { client -> client.postService(organizationId, name, cluster, namespace) }

    private suspend fun <T> withAppClient(block: suspend (HttpClient) -> T): T {
        var result: T? = null
        testApplication {
            application { module(initDatabase = false) }
            result = block(createJsonClient())
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private suspend fun HttpClient.postOrganization(name: String): CreatedOrganization {
        val response =
            post("/api/organizations") {
                contentType(ContentType.Application.Json)
                setBody(CreateOrganizationRequest(name = name))
            }
        return lenientJson.decodeFromString<CreatedOrganization>(response.bodyAsText())
    }

    private suspend fun HttpClient.postService(
        organizationId: String,
        name: String,
        cluster: String,
        namespace: String,
    ): CreatedService {
        val response =
            post("/api/services") {
                contentType(ContentType.Application.Json)
                setBody(
                    CreateServiceRequest(
                        organizationId = organizationId,
                        cluster = cluster,
                        namespace = namespace,
                        name = name,
                    ),
                )
            }
        return lenientJson.decodeFromString<CreatedService>(response.bodyAsText())
    }

    private fun ApplicationTestBuilder.createJsonClient(): HttpClient =
        createClient {
            install(ClientContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
}
