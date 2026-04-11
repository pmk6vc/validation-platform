package com.platform.agent.models

import kotlinx.serialization.Serializable

/**
 * Represents a single API call entry from the Kubeshark WebSocket /wsFull stream.
 *
 * The WebSocket pushes one JSON object per captured L7 transaction. This DTO
 * captures the subset of fields the agent needs for traffic capture:
 * identity, timing, source/destination, and HTTP request/response data.
 *
 * Fields not needed by the agent (protocol metadata, PCAP refs, checksums, etc.)
 * are ignored via `ignoreUnknownKeys = true` on the Json instance.
 */
@Serializable
data class KubesharkEntry(
    val id: String,
    val timestamp: Long,
    val protocol: KubesharkProtocol? = null,
    val tls: Boolean = false,
    val src: KubesharkEndpoint? = null,
    val dst: KubesharkEndpoint? = null,
    val request: KubesharkRequest? = null,
    val response: KubesharkResponse? = null,
    val requestSize: Long? = null,
    val responseSize: Long? = null,
    val elapsedTime: Long? = null,
)

@Serializable
data class KubesharkProtocol(
    val name: String,
    val abbr: String? = null,
)

@Serializable
data class KubesharkEndpoint(
    val ip: String? = null,
    val port: String? = null,
    val name: String? = null,
    val namespace: String? = null,
)

/**
 * HTTP request from the WebSocket stream.
 * Headers are in HAR format: list of {name, value} objects.
 * Request body is in `postData.text` (HAR format, plaintext).
 */
@Serializable
data class KubesharkRequest(
    val method: String? = null,
    val url: String? = null,
    val path: String? = null,
    val headers: List<KubesharkHeader>? = null,
    val postData: KubesharkPostData? = null,
    val bodySize: Long? = null,
)

/**
 * HAR postData block carrying the HTTP request body.
 * `text` is the raw body; Kubeshark does NOT base64-encode request bodies.
 */
@Serializable
data class KubesharkPostData(
    val text: String? = null,
    val mimeType: String? = null,
)

/**
 * HTTP response from the WebSocket stream.
 * Body is in `content.text` (HAR format).
 */
@Serializable
data class KubesharkResponse(
    val status: Int? = null,
    val statusText: String? = null,
    val headers: List<KubesharkHeader>? = null,
    val content: KubesharkContent? = null,
    val bodySize: Long? = null,
)

@Serializable
data class KubesharkHeader(
    val name: String,
    val value: String,
)

/**
 * HAR content block carrying the HTTP response body.
 * Kubeshark base64-encodes response bodies (binary-safe) and sets
 * [encoding] to "base64"; the client must decode before use.
 */
@Serializable
data class KubesharkContent(
    val text: String? = null,
    val mimeType: String? = null,
    val encoding: String? = null,
    val size: Long? = null,
)
