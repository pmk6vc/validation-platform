# Cloud SQL — PostgreSQL 16, db-f1-micro, us-central1.
# activation_policy is controlled by var.cloudsql_active so the instance can be
# paused outside business hours to reduce cost (~$2.50/mo vs ~$7/mo).

resource "google_sql_database_instance" "postgres" {
  name             = "validation-postgres"
  database_version = "POSTGRES_16"
  region           = var.region

  settings {
    # ENTERPRISE edition is required for the cheap shared-core tiers like
    # db-f1-micro. ENTERPRISE_PLUS (the new default) only supports
    # db-perf-optimized-N-* tiers which are ~20× more expensive.
    edition           = "ENTERPRISE"
    tier              = "db-f1-micro"
    activation_policy = var.cloudsql_active ? "ALWAYS" : "NEVER"

    backup_configuration {
      enabled = true
    }

    ip_configuration {
      # Cloud Run connects via the Cloud SQL Auth Proxy connector, which
      # uses IAM auth and a TLS tunnel — the public IP is never reachable
      # without IAM credentials. Avoiding private IP keeps us from having
      # to set up VPC Service Networking peering on the default network.
      ipv4_enabled = true
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

# Read the DB password from Secret Manager at apply time. The user must
# populate validation-db-password BEFORE the first terraform apply
# (bootstrap.sh prints the gcloud command for this).
data "google_secret_manager_secret_version" "db_password" {
  secret = google_secret_manager_secret.db_password.secret_id
}

resource "google_sql_user" "platform" {
  name     = "platform"
  instance = google_sql_database_instance.postgres.name
  password = data.google_secret_manager_secret_version.db_password.secret_data
  # Note: this puts the password value in Terraform state. The state lives
  # in GCS, encrypted at rest, with restricted IAM — standard tradeoff.
}
