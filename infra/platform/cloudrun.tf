# Cloud Run services: validation-platform (port 8080) and validation-collector (port 8081).
#
# Both services:
#   - Run as validation-platform-sa
#   - Connect to Cloud SQL via the Cloud SQL Auth Proxy connector
#   - Receive DATABASE_PASSWORD and JWT_PRIVATE_KEY from Secret Manager at runtime
#   - Validate JWT in-app via the shared installJwtAuth() — no edge proxy
#
# Images are required (no defaults) — the CI/CD deploy workflow passes them
# as -var arguments after building and pushing to Artifact Registry:
#   terraform apply -var=platform_image=<sha-tag> -var=collector_image=<sha-tag>

locals {
  cloud_sql_connection = google_sql_database_instance.postgres.connection_name
}

# ---------------------------------------------------------------------------
# validation-platform  (port 8080)
# ---------------------------------------------------------------------------

resource "google_cloud_run_v2_service" "platform" {
  name     = "validation-platform"
  location = var.region
  project  = var.project

  template {
    service_account = google_service_account.platform.email

    containers {
      image = var.platform_image

      ports {
        container_port = 8080
      }

      # PORT is set automatically by Cloud Run from ports.container_port —
      # it's a reserved env name, can't be overridden manually.

      env {
        name = "DATABASE_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.db_password.secret_id
            version = "latest"
          }
        }
      }

      env {
        name = "JWT_PRIVATE_KEY"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.jwt_private_key.secret_id
            version = "latest"
          }
        }
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

  template {
    service_account = google_service_account.platform.email

    containers {
      image = var.collector_image

      ports {
        container_port = 8081
      }

      # PORT is set automatically by Cloud Run from ports.container_port.

      env {
        name = "DATABASE_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.db_password.secret_id
            version = "latest"
          }
        }
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
