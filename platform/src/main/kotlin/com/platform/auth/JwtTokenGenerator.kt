package com.platform.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Date

/**
 * CLI tool for generating signed JWTs for agent authentication.
 *
 * Usage:
 *   ./gradlew :platform:generateToken --args="--org org-123 --cluster prod"
 *
 * Reads the RSA private key from JWT_PRIVATE_KEY env var (pipe-delimited PEM).
 * Prints the signed JWT to stdout.
 */
fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    val orgId = parsed["--org"] ?: error("--org is required")
    val cluster = parsed["--cluster"] ?: error("--cluster is required")
    val expiryDays = parsed["--expiry-days"]?.toLongOrNull() ?: 365

    val privateKeyPem =
        System.getenv("JWT_PRIVATE_KEY")?.replace("|", "\n")
            ?: error("JWT_PRIVATE_KEY env var is required")

    val keyBytes =
        Base64.getDecoder().decode(
            privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), ""),
        )
    val keyFactory = KeyFactory.getInstance("RSA")
    val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyBytes)) as RSAPrivateCrtKey
    val publicKey =
        keyFactory.generatePublic(
            RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent),
        ) as RSAPublicKey

    val token =
        JWT
            .create()
            .withClaim("organizationId", orgId)
            .withClaim("cluster", cluster)
            .withIssuedAt(Date())
            .withExpiresAt(Date.from(Instant.now().plus(expiryDays, ChronoUnit.DAYS)))
            .sign(Algorithm.RSA256(publicKey, privateKey))

    println(token)
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size - 1) {
        if (args[i].startsWith("--")) {
            map[args[i]] = args[i + 1]
            i += 2
        } else {
            i++
        }
    }
    return map
}
