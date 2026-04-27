package com.platform.shared.secrets

import com.google.cloud.secretmanager.v1.SecretManagerServiceClient
import com.google.cloud.secretmanager.v1.SecretVersionName

/**
 * Reads secrets from Google Cloud Secret Manager.
 *
 * Used on Cloud Run where each env var holds a Secret Manager resource name
 * (e.g. `projects/my-project/secrets/db-password/versions/latest`) rather
 * than the secret value itself. Authentication is via Workload Identity —
 * the Cloud Run service account requires `roles/secretmanager.secretAccessor`.
 *
 * The env var named [name] must contain a fully-qualified Secret Manager
 * resource name in the format:
 *   `projects/{project}/secrets/{secret}/versions/{version}`
 */
class GcpSecretsProvider : SecretsProvider {
    override fun get(name: String): String {
        val resourceName =
            System.getenv(name)
                ?: error("Required env var '$name' not set (expected a Secret Manager resource name)")

        return SecretManagerServiceClient.create().use { client ->
            val secretVersionName = SecretVersionName.parse(resourceName)
            val response = client.accessSecretVersion(secretVersionName)
            response.payload.data.toStringUtf8()
        }
    }
}
