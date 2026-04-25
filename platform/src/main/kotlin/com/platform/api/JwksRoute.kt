package com.platform.api

import com.platform.auth.derivePublicKey
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import java.security.interfaces.RSAPublicKey
import java.util.Base64

private val logger = LoggerFactory.getLogger("JwksRoute")

/**
 * Serves the platform's RSA public key at `/.well-known/jwks.json`.
 *
 * Envoy fetches this endpoint via `remote_jwks` to validate JWT signatures.
 * The public key is derived from the RSA private key provided via [privateKeyPem].
 * This is the same pattern used by Google, Auth0, and every OIDC provider.
 *
 * The endpoint is unauthenticated — Envoy needs it before it can validate anything.
 */
fun Application.configureJwks(privateKeyPem: String? = System.getenv("JWT_PRIVATE_KEY")?.replace("|", "\n")) {
    if (privateKeyPem.isNullOrBlank()) {
        logger.warn("JWT_PRIVATE_KEY not set — JWKS endpoint will return empty key set")
        routing {
            get("/.well-known/jwks.json") {
                call.respondText("""{"keys":[]}""", ContentType.Application.Json)
            }
        }
        return
    }

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
