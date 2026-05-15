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
 *
 * [redactionSalt], [extraRedactedHeaders], [extraBodyRedactionPatterns] added
 * in Plan 01-03 (CAPTURE-09 + CONTEXT.md D-17). Phase 1 wires the
 * salt (sourced from organizations.redaction_salt); the extra-allowlist
 * fields ship empty — Phase 3 SEC-09 wires the platform endpoint that
 * populates them. Additive evolution: pre-update agents that ignore
 * unknown fields keep working.
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
    val redactionSalt: String = "",
    val extraRedactedHeaders: List<String> = emptyList(),
    val extraBodyRedactionPatterns: List<String> = emptyList(),
)

/**
 * organizationId and cluster are NOT body fields — both are taken from the JWT
 * (organizationId + cluster claims). This prevents an agent token scoped to one
 * org/cluster from registering services in another.
 */
@Serializable
data class CreateServiceRequest(
    val namespace: String,
    val name: String,
    val provider: Provider = Provider.UNKNOWN,
    val metadata: Map<String, String>? = null,
)
