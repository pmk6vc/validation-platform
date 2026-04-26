# Cloud Run services: validation-platform (port 8080) and validation-collector (port 8081).
#
# Both services:
#   - Run as validation-platform-sa
#   - Connect to Cloud SQL via the Cloud SQL Auth Proxy connector
#   - Read DATABASE_PASSWORD and JWT_PRIVATE_KEY at startup via the Secret Manager SDK
#     using the resource names supplied in env vars (SECRETS_PROVIDER=gcp).
#     Cloud Run no longer injects plaintext secret values into the environment.
#   - Validate JWT in-app via the shared installJwtAuth() — no edge proxy
#
# Images are required (no defaults) — the CI/CD deploy workflow passes them
# as -var arguments after building and pushing to Artifact Registry:
#   terraform apply -var=platform_image=<sha-tag> -var=collector_image=<sha-tag>

locals {
  cloud_sql_connection = google_sql_database_instance.postgres.connection_name

  # JDBC URL for Cloud SQL via the cloud-sql-jdbc-socket-factory library.
  # enableIamAuth=true makes the factory authenticate as the IAM service
  # account (no DB password) — see infra/platform/iam.tf and the
  # google_sql_user typed CLOUD_IAM_SERVICE_ACCOUNT below.
  database_jdbc_url = "jdbc:postgresql:///${google_sql_database.validation.name}?cloudSqlInstance=${local.cloud_sql_connection}&socketFactory=com.google.cloud.sql.postgres.SocketFactory&enableIamAuth=true"

  # Fully-qualified Secret Manager resource name. The app resolves it at
  # startup via the SDK (SECRETS_PROVIDER=gcp).
  jwt_private_key_resource_name = "projects/${var.project}/secrets/${google_secret_manager_secret.jwt_private_key.secret_id}/versions/latest"
}

# ---------------------------------------------------------------------------
# validation-platform  (port 8080)
# ---------------------------------------------------------------------------

resource "google_cloud_run_v2_service" "platform" {
  name     = "validation-platform"
  location = var.region
  project  = var.project

  # Cloud Run services are stateless — durability lives in Cloud SQL.
  # Protecting them from deletion only adds friction in platform-delete.sh.
  deletion_protection = false

  template {
    service_account = google_service_account.platform.email

    containers {
      image = var.platform_image

      ports {
        container_port = 8080
      }

      # PORT is set automatically by Cloud Run from ports.container_port —
      # it's a reserved env name, can't be overridden manually.

      # SECRETS_PROVIDER=gcp instructs the app to resolve secret values via the
      # Secret Manager SDK rather than reading them as literal env var values.
      env {
        name  = "SECRETS_PROVIDER"
        value = "gcp"
      }

      # Cloud SQL via JDBC socket factory + IAM auth. The DB user IS the
      # service account; no password — the socket factory swaps in a
      # short-lived OAuth token. See iam.tf for the cloudsql.instanceUser
      # role and cloudsql.tf for the CLOUD_IAM_SERVICE_ACCOUNT user.
      env {
        name  = "DATABASE_URL"
        value = local.database_jdbc_url
      }
      env {
        name  = "DATABASE_USER"
        value = google_service_account.platform.email
      }
      env {
        name  = "DATABASE_AUTH_MODE"
        value = "iam"
      }

      # JWT signing key — resource name resolved by GcpSecretsProvider.
      env {
        name  = "JWT_PRIVATE_KEY"
        value = local.jwt_private_key_resource_name
      }

      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
      }
    }

    volumes {
      name = "cloudsql"
      cloud_sql_instance {
        instances = [local.cloud_sql_connection]
      }
    }

    scaling {
      min_instance_count = 0
      max_instance_count = 3
    }
  }

  depends_on = [
    google_project_service.apis,
    google_secret_manager_secret.db_password,
    google_secret_manager_secret.jwt_private_key,
  ]
}

# Public invoker: the platform validates JWTs in-app (Ktor JWT plugin via
# shared installJwtAuth). Unauthenticated requests get 401 from the app
# itself — Cloud Run IAM is not the auth layer here.
resource "google_cloud_run_v2_service_iam_member" "platform_public" {
  project  = var.project
  location = var.region
  name     = google_cloud_run_v2_service.platform.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

# ---------------------------------------------------------------------------
# validation-collector  (port 8081)
# ---------------------------------------------------------------------------

resource "google_cloud_run_v2_service" "collector" {
  name     = "validation-collector"
  location = var.region
  project  = var.project

  # See platform — stateless services don't need deletion protection.
  deletion_protection = false

  template {
    service_account = google_service_account.platform.email

    containers {
      image = var.collector_image

      ports {
        container_port = 8081
      }

      # PORT is set automatically by Cloud Run from ports.container_port.

      # SECRETS_PROVIDER=gcp instructs the app to resolve secret values via the
      # Secret Manager SDK rather than reading them as literal env var values.
      env {
        name  = "SECRETS_PROVIDER"
        value = "gcp"
      }

      # Cloud SQL via JDBC socket factory + IAM auth (see platform service for details).
      env {
        name  = "DATABASE_URL"
        value = local.database_jdbc_url
      }
      env {
        name  = "DATABASE_USER"
        value = google_service_account.platform.email
      }
      env {
        name  = "DATABASE_AUTH_MODE"
        value = "iam"
      }

      # JWT signing key — resource name resolved by GcpSecretsProvider.
      env {
        name  = "JWT_PRIVATE_KEY"
        value = local.jwt_private_key_resource_name
      }

      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
      }
    }

    volumes {
      name = "cloudsql"
      cloud_sql_instance {
        instances = [local.cloud_sql_connection]
      }
    }

    scaling {
      min_instance_count = 0
      max_instance_count = 3
    }
  }

  depends_on = [
    google_project_service.apis,
    google_secret_manager_secret.db_password,
    google_secret_manager_secret.jwt_private_key,
  ]
}

# Public invoker: same in-app JWT validation as platform — see platform_public.
resource "google_cloud_run_v2_service_iam_member" "collector_public" {
  project  = var.project
  location = var.region
  name     = google_cloud_run_v2_service.collector.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}
