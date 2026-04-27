package com.platform.shared.secrets

/**
 * Abstraction for reading secrets at application startup.
 *
 * Two implementations:
 * - [EnvSecretsProvider] reads the literal env var value (local/docker/k8s).
 * - [GcpSecretsProvider] treats the env var value as a Secret Manager resource
 *   name and resolves the actual secret via the SDK (Cloud Run + Workload Identity).
 *
 * Selection is driven by the SECRETS_PROVIDER env var (default: "env").
 */
interface SecretsProvider {
    /**
     * Returns the secret value for [name].
     *
     * @param name the identifier used to look up the secret — an env var name for
     *   [EnvSecretsProvider], or a Secret Manager resource name for [GcpSecretsProvider].
     * @throws IllegalStateException if the secret cannot be resolved.
     */
    fun get(name: String): String
}

/**
 * Builds the appropriate [SecretsProvider] from the SECRETS_PROVIDER environment variable.
 *
 * | SECRETS_PROVIDER | Provider             | Use case                          |
 * |------------------|----------------------|-----------------------------------|
 * | `env` (default)  | [EnvSecretsProvider] | Local dev, Docker, Minikube       |
 * | `gcp`            | [GcpSecretsProvider] | Cloud Run via Workload Identity   |
 */
fun secretsProviderFromEnvironment(): SecretsProvider =
    when (val mode = System.getenv("SECRETS_PROVIDER") ?: "env") {
        "env" -> EnvSecretsProvider()
        "gcp" -> GcpSecretsProvider()
        else -> error("Unknown SECRETS_PROVIDER value: '$mode'. Expected 'env' or 'gcp'.")
    }
