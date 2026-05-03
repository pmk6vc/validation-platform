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

    # Enable IAM database authentication. Required for the
    # CLOUD_IAM_SERVICE_ACCOUNT user below — Cloud Run authenticates as
    # the service account, no password.
    database_flags {
      name  = "cloudsql.iam_authentication"
      value = "on"
    }
  }

  # Prevent accidental deletion of the database instance.
  # Controlled by var.cloudsql_deletion_protection so platform-delete.sh
  # can flip it to false in the same terraform apply that runs before destroy.
  deletion_protection = var.cloudsql_deletion_protection

  depends_on = [google_project_service.apis]
}

resource "google_sql_database" "validation" {
  name     = "validation"
  instance = google_sql_database_instance.postgres.name
}

# Cloud Run authenticates to Postgres as its service account via IAM.
# The "name" must be the SA email; type CLOUD_IAM_SERVICE_ACCOUNT tells
# Cloud SQL to expect an OAuth token rather than a password. No secret
# rotation, no password in Terraform state, no Secret Manager involvement.
resource "google_sql_user" "platform_sa" {
  # Cloud SQL requires the ".gserviceaccount.com" suffix stripped from the
  # IAM SA database username. See cloudrun.tf:database_iam_user for the
  # matching DATABASE_USER env var the app authenticates with.
  name     = trimsuffix(google_service_account.platform.email, ".gserviceaccount.com")
  instance = google_sql_database_instance.postgres.name
  type     = "CLOUD_IAM_SERVICE_ACCOUNT"
}

# Developer human accounts (var.dev_db_users) — see variables.tf for the
# rationale and the list of caveats. Username for CLOUD_IAM_USER is the
# full Google account email (no suffix trimming).
#
# These users have no SQL privileges by default. After `terraform apply`
# the schema owner (platform SA) must run a one-time GRANT, scoped to
# whatever access is intended. Example for read-only access:
#
#   GRANT USAGE ON SCHEMA public TO "varun@example.com";
#   GRANT SELECT ON ALL TABLES IN SCHEMA public TO "varun@example.com";
#   ALTER DEFAULT PRIVILEGES IN SCHEMA public
#     GRANT SELECT ON TABLES TO "varun@example.com";
resource "google_sql_user" "dev_users" {
  for_each = toset(var.dev_db_users)
  name     = each.value
  instance = google_sql_database_instance.postgres.name
  type     = "CLOUD_IAM_USER"
}
