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
    val pod: KubesharkPod? = null,
)

/**
 * Minimal projection of Kubeshark's `dst.pod` field. Kubeshark embeds the
 * full K8s Pod object (metadata, spec, status — large) but the agent only
 * needs `metadata.labels` for service attribution. `ignoreUnknownKeys=true`
 * on the `Json` instance discards everything else.
 */
@Serializable
data class KubesharkPod(
    val metadata: KubesharkPodMetadata? = null,
)

@Serializable
data class KubesharkPodMetadata(
    val labels: Map<String, String>? = null,
)

/**
 * HTTP request from the WebSocket stream.
 *
 * Kubeshark v53 emits headers as a JSON object (`{"Accept":"...","Host":"..."}`),
 * not the HAR-spec list-of-{name,value} pairs the docs imply. We mirror the wire
 * format directly — coercing into HAR shape would just be lossy round-tripping
 * since the collector stores headers as a map anyway.
 *
 * Request body is in `postData.text` (plaintext, not base64 like response bodies).
 */
@Serializable
data class KubesharkRequest(
    val method: String? = null,
    val url: String? = null,
    val path: String? = null,
    val headers: Map<String, String>? = null,
    val postData: KubesharkPostData? = null,
    val bodySize: Long? = null,
)

/**
 * HAR `postData` block carrying the HTTP request body.
 *
 * **The name is a HAR-spec historical quirk, not a method restriction.**
 * HAR (HTTP Archive, https://w3c.github.io/web-performance/specs/HAR/Overview.html)
 * calls the request-body wrapper `postData` because HTTP request bodies were
 * originally associated with POST. In practice, `postData` is populated for
 * ANY HTTP request that carries a body — POST, PUT, PATCH, and even DELETE
 * when the client sends one. Kubeshark follows the HAR convention verbatim,
 * so we do too.
 *
 * `text` is the raw body as a string. Unlike [KubesharkContent] (used for
 * response bodies), Kubeshark does NOT base64-encode request bodies — they
 * come through as plaintext and can be forwarded directly.
 */
@Serializable
data class KubesharkPostData(
    val text: String? = null,
    val mimeType: String? = null,
)

/**
 * HTTP response from the WebSocket stream.
 *
 * Headers are a JSON object — see [KubesharkRequest] for why. Body is in
 * `content.text`; Kubeshark base64-encodes it (set [KubesharkContent.encoding]
 * to `"base64"`) so non-UTF-8 payloads survive the wire.
 */
@Serializable
data class KubesharkResponse(
    val status: Int? = null,
    val statusText: String? = null,
    val headers: Map<String, String>? = null,
    val content: KubesharkContent? = null,
    val bodySize: Long? = null,
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
