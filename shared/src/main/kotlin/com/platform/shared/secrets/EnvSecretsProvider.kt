package com.platform.shared.secrets

/**
 * Reads secrets from environment variables.
 *
 * Used in local development, Docker Compose, and Minikube where secret values
 * are injected directly as env var values (not resource names).
 */
class EnvSecretsProvider : SecretsProvider {
    override fun get(name: String): String =
        System.getenv(name)
            ?: error("Required secret '$name' not found in environment variables")
}
