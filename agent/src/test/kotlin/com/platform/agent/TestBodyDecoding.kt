package com.platform.agent

import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import java.util.zip.GZIPInputStream

/**
 * Read the request body as a UTF-8 string, transparently decompressing
 * gzip-encoded bodies. CollectorClient runs every POST through Ktor's
 * ContentEncoding plugin (gzip), which puts Content-Encoding on the
 * OutgoingContent's headers, not the request headers map.
 */
internal suspend fun HttpRequestData.bodyAsDecodedString(): String {
    val raw = body.toByteArray()
    return if (body.headers[HttpHeaders.ContentEncoding] == "gzip") {
        GZIPInputStream(raw.inputStream()).use { it.readAllBytes() }.decodeToString()
    } else {
        raw.decodeToString()
    }
}
