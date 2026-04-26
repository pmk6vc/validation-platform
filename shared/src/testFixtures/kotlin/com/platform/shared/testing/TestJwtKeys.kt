package com.platform.shared.testing

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * Single source of truth for the RSA keypair used to sign and verify JWTs in
 * the test suite. Both platform and collector tests should use these keys —
 * the shared `installJwtAuth(privateKeyPem)` derives its public key from the
 * same PEM, so tokens minted with [generateTestJwt] verify against any app
 * that was bootstrapped with [privateKeyPem].
 *
 * Tests that intentionally need a *different* keypair (e.g. "wrong signing
 * key returns 401" tests) should generate their own keypair locally — those
 * are testing the rejection path and shouldn't share the trusted key.
 */
object TestJwtKeys {
    private val keyPair: java.security.KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    val privateKey: RSAPrivateKey = keyPair.private as RSAPrivateKey
    val publicKey: RSAPublicKey = keyPair.public as RSAPublicKey

    /** PEM-encoded private key. Pass to `installJwtAuth(privateKeyPem)`. */
    val privateKeyPem: String by lazy {
        val encoded = Base64.getEncoder().encodeToString(privateKey.encoded)
        "-----BEGIN PRIVATE KEY-----\n" +
            encoded.chunked(64).joinToString("\n") +
            "\n-----END PRIVATE KEY-----"
    }

    /**
     * Mint a JWT signed with [privateKey]. Defaults give a generic 1-hour token
     * with a placeholder org and `cluster = prod`. Override per-test for
     * authorization-scoped assertions (e.g. multi-tenant isolation).
     */
    fun generateTestJwt(
        organizationId: String = DEFAULT_ORG_ID,
        cluster: String = "prod",
        role: String? = null,
        expiresAt: Date = Date.from(Instant.now().plusSeconds(3600)),
    ): String {
        val builder =
            JWT
                .create()
                .withClaim("organizationId", organizationId)
                .withClaim("cluster", cluster)
                .withIssuedAt(Date())
                .withExpiresAt(expiresAt)
        if (role != null) builder.withClaim("role", role)
        return builder.sign(Algorithm.RSA256(publicKey, privateKey))
    }

    /** Default placeholder org for tests that don't care about the principal's identity. */
    const val DEFAULT_ORG_ID: String = "00000000-0000-0000-0000-000000000001"
}

/** Convenience: generic-purpose token with the default placeholder claims. */
val defaultTestToken: String by lazy { TestJwtKeys.generateTestJwt() }
