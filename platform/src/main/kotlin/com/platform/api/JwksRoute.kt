package com.platform.api

import com.platform.shared.auth.derivePublicKey
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * Serves the platform's RSA public key at `/.well-known/jwks.json`.
 *
 * The endpoint is unauthenticated. The public key is derived from the RSA
 * private key passed in via [privateKeyPem]; the caller (Application.module)
 * resolves it from the JWT_PRIVATE_KEY env var once at startup.
 */
fun Application.configureJwks(privateKeyPem: String) {
    val publicKey = derivePublicKey(privateKeyPem)
    val jwksJson = buildJwksJson(publicKey)

    routing {
        get("/.well-known/jwks.json") {
            call.respondText(jwksJson, ContentType.Application.Json)
        }
    }
}

private fun buildJwksJson(publicKey: RSAPublicKey): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val n = encoder.encodeToString(publicKey.modulus.toByteArray().let { stripLeadingZero(it) })
    val e = encoder.encodeToString(publicKey.publicExponent.toByteArray())

    return """{"keys":[{"kty":"RSA","alg":"RS256","use":"sig","n":"$n","e":"$e"}]}"""
}

/** RSA modulus BigInteger.toByteArray() may include a leading zero byte for sign — strip it for JWKS. */
private fun stripLeadingZero(bytes: ByteArray): ByteArray =
    if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
