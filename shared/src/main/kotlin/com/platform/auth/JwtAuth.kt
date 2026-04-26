package com.platform.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.platform.models.OrganizationId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

const val JWT_AUTH = "jwt-auth"

/**
 * Installs Ktor's JWT authentication plugin, validating RS256 tokens signed
 * with the platform's own private key.
 *
 * The public key is derived from the [JWT_PRIVATE_KEY] environment variable
 * (the same key the platform uses to sign tokens), avoiding an HTTP round-trip
 * to the JWKS endpoint.
 *
 * Required claims:
 * - `organizationId`: UUID string identifying the tenant
 * - `cluster`: string identifying the Kubernetes cluster
 *
 * Optional claims:
 * - `role`: caller role (e.g. "admin")
 *
 * The resolved [AgentIdentity] principal is available via
 * `call.principal<AgentIdentity>()` inside authenticated routes.
 */
fun Application.installJwtAuth(privateKeyPem: String) {
    val publicKey = derivePublicKey(privateKeyPem)
    val algorithm = Algorithm.RSA256(publicKey, null)
    val verifier = JWT.require(algorithm).build()

    install(Authentication) {
        jwt(JWT_AUTH) {
            verifier(verifier)

            validate { credential ->
                val organizationId =
                    credential.payload.getClaim("organizationId")?.asString()
                        ?: return@validate null

                val cluster =
                    credential.payload.getClaim("cluster")?.asString()
                        ?: return@validate null

                val orgId =
                    try {
                        OrganizationId(organizationId)
                    } catch (_: IllegalArgumentException) {
                        return@validate null
                    }

                val role = credential.payload.getClaim("role")?.asString()

                AgentIdentity(orgId, cluster, role)
            }

            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, "Invalid or missing JWT")
            }
        }
    }
}

/**
 * Derive the RSA public key from a PEM-encoded private key string.
 * The private key PEM may use pipe characters (`|`) in place of newlines,
 * which is how [JWT_PRIVATE_KEY] is stored in environment variables.
 */
fun derivePublicKey(privateKeyPem: String): RSAPublicKey {
    val normalized = privateKeyPem.replace("|", "\n")
    val keyBytes =
        Base64.getDecoder().decode(
            normalized
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), ""),
        )
    val keyFactory = KeyFactory.getInstance("RSA")
    val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyBytes)) as RSAPrivateCrtKey
    return keyFactory.generatePublic(
        RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent),
    ) as RSAPublicKey
}
