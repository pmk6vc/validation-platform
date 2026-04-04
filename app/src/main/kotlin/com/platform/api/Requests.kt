package com.platform.api

import com.platform.models.Provider
import kotlinx.serialization.Serializable

@Serializable
data class CreateOrganizationRequest(
    val name: String,
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
