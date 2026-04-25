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

# ---------------------------------------------------------------------------
# validation-sandbox-sa
# Used by GKE workloads via Workload Identity (future: agent running in sandbox).
# No project roles yet — placeholder for future agent deployment.
# ---------------------------------------------------------------------------

resource "google_service_account" "sandbox" {
  account_id   = "validation-sandbox-sa"
  display_name = "Validation Sandbox (GKE Workload Identity)"
  project      = var.project

  depends_on = [google_project_service.apis]
}

# Allow Kubernetes service accounts in the sandbox cluster to impersonate
# this GCP SA via Workload Identity.
# Namespace and KSA name placeholders — update when the agent is deployed.
resource "google_service_account_iam_member" "sandbox_workload_identity" {
  service_account_id = google_service_account.sandbox.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project}.svc.id.goog[validation/validation-agent]"
}
