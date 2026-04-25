# Cloud SQL — PostgreSQL 16, db-f1-micro, us-central1.
# activation_policy is controlled by var.cloudsql_active so the instance can be
# paused outside business hours to reduce cost (~$2.50/mo vs ~$7/mo).

resource "google_sql_database_instance" "postgres" {
  name             = "validation-postgres"
  database_version = "POSTGRES_16"
  region           = var.region

  settings {
    tier              = "db-f1-micro"
    activation_policy = var.cloudsql_active ? "ALWAYS" : "NEVER"

    backup_configuration {
      enabled = true
    }

    ip_configuration {
      # Cloud Run connects via Cloud SQL Auth Proxy (Unix socket).
      # No public IP is required for Cloud Run + Cloud SQL connector.
      ipv4_enabled = false

      private_network = "projects/${var.project}/global/networks/default"
    }

    database_flags {
      name  = "max_connections"
      value = "100"
    }
  }

  # Prevent accidental deletion of the database instance.
  deletion_protection = true

  depends_on = [google_project_service.apis]
}

resource "google_sql_database" "validation" {
  name     = "validation"
  instance = google_sql_database_instance.postgres.name
}

resource "google_sql_user" "platform" {
  name     = "platform"
  instance = google_sql_database_instance.postgres.name
  # Password is managed via Secret Manager (validation-db-password).
  # Set it manually: gcloud secrets versions add validation-db-password --data-file=-
  password = null
}
