package com.platform.api

import com.platform.models.Provider
import kotlinx.serialization.Serializable

@Serializable
data class CreateOrganizationRequest(
    val name: String,
)

/**
 * Response for GET /api/agent/config. Matches the agent's DynamicConfig wire format:
 * duration fields are Long milliseconds, not ISO strings.
 */
@Serializable
data class AgentConfigResponse(
    val targetServices: Map<String, String> = emptyMap(),
    val samplingRate: Double = 1.0,
    val batchSize: Int = 100,
    val captureInterval: Long = 5000,
    val configPollInterval: Long = 30000,
    val discoveryInterval: Long = 60000,
    val namespaceFilters: List<String> = emptyList(),
)

@Serializable
data class CreateServiceRequest(
    val organizationId: String,
    val cluster: String,
    val namespace: String,
    val name: String,
    val provider: Provider = Provider.UNKNOWN,
    val metadata: Map<String, String>? = null,
)
