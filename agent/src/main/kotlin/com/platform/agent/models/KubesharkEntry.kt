package com.platform.agent.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class KubesharkResponse(
    val calls: List<KubesharkEntry>,
    val truncated: Boolean = false,
)

@Serializable
data class KubesharkEntry(
    val id: String,
    val ts: Long,
    val src: KubesharkEndpoint? = null,
    val dst: KubesharkEndpoint? = null,
    val proto: String,
    @SerialName("sub_proto") val subProto: String? = null,
    val method: String? = null,
    val url: String? = null,
    val path: String? = null,
    @SerialName("req_headers") val reqHeaders: Map<String, String>? = null,
    @SerialName("req_body") val reqBody: String? = null,
    @SerialName("resp_headers") val respHeaders: Map<String, String>? = null,
    @SerialName("resp_body") val respBody: String? = null,
    val status: Int? = null,
    @SerialName("req_size") val reqSize: Long? = null,
    @SerialName("resp_size") val respSize: Long? = null,
)

@Serializable
data class KubesharkEndpoint(
    val name: String? = null,
    val ns: String? = null,
    val svc: String? = null,
    val ip: String? = null,
    val port: Int? = null,
    val labels: Map<String, String>? = null,
)
