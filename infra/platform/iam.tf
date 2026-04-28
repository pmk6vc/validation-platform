# Service Accounts and IAM bindings for the platform stack.

# ---------------------------------------------------------------------------
# validation-platform-sa
# Used by both Cloud Run services (platform + collector).
# Needs: Cloud SQL client, Secret Manager accessor.
# ---------------------------------------------------------------------------

resource "google_service_account" "platform" {
  account_id   = "validation-platform-sa"
  display_name = "Validation Platform (Cloud Run)"
  project      = var.project

  depends_on = [google_project_service.apis]
}

resource "google_project_iam_member" "platform_cloudsql_client" {
  project = var.project
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.platform.email}"
}

# Required for IAM database authentication (logging in as the SA).
# Pairs with cloudsql.iam_authentication=on flag and the
# CLOUD_IAM_SERVICE_ACCOUNT google_sql_user — see cloudsql.tf.
resource "google_project_iam_member" "platform_cloudsql_instance_user" {
  project = var.project
  role    = "roles/cloudsql.instanceUser"
  member  = "serviceAccount:${google_service_account.platform.email}"
}

resource "google_project_iam_member" "platform_secret_accessor" {
  project = var.project
  role    = "roles/secretmanager.secretAccessor"
  member  = "serviceAccount:${google_service_account.platform.email}"
}

# Workload Identity binding for Cloud Run:
# Cloud Run already runs as the SA itself — the SA acts as its own identity.
# We bind the Cloud Run service agent so the SA can act on its own behalf.
resource "google_service_account_iam_member" "platform_workload_identity_self" {
  service_account_id = google_service_account.platform.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:${google_service_account.platform.email}"
}

# validation-sandbox-sa was removed: the WIF binding referenced
# <project>.svc.id.goog, which only exists once a GKE cluster has been
# created with that workload pool — chicken-and-egg with the sandbox stack.
# We'll re-add this (in infra/sandbox/iam.tf) when the agent actually goes
# into the sandbox cluster as part of the customer-onboarding work.
