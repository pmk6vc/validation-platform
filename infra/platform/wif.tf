# Workload Identity Federation — allows GitHub Actions to authenticate to GCP
# via OIDC without any service account JSON keys.
#
# After applying this stack, copy the two outputs to GitHub repo variables:
#   WIF_PROVIDER  = cicd_workload_identity_provider
#   CICD_SA       = cicd_service_account_email

# Fetch the numeric project number — used to build the WIF principal set URI.
data "google_project" "project" {
  project_id = var.project
}

# ---------------------------------------------------------------------------
# Workload Identity Pool + OIDC Provider for GitHub Actions
# ---------------------------------------------------------------------------

resource "google_iam_workload_identity_pool" "github_actions" {
  workload_identity_pool_id = "github-actions"
  display_name              = "GitHub Actions"
  description               = "Identity pool for GitHub Actions OIDC tokens"
  project                   = var.project

  depends_on = [google_project_service.apis]
}

resource "google_iam_workload_identity_pool_provider" "github" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github_actions.workload_identity_pool_id
  workload_identity_pool_provider_id = "github"
  display_name                       = "GitHub OIDC"
  project                            = var.project

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }

  # Map GitHub token claims to Google attributes used in conditions and
  # principal set bindings.
  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
    "attribute.ref"        = "assertion.ref"
  }

  # Only accept tokens from the validation-platform repository.
  # Required by Google provider >= 5.x when attribute conditions reference
  # attributes derived from assertion claims.
  attribute_condition = "assertion.repository == 'pmk6vc/validation-platform'"
}

# ---------------------------------------------------------------------------
# CI/CD Service Account
# ---------------------------------------------------------------------------

resource "google_service_account" "cicd" {
  account_id   = "validation-cicd-sa"
  display_name = "GitHub Actions CI/CD"
  project      = var.project

  depends_on = [google_project_service.apis]
}

# Allow the SA to push images to Artifact Registry.
resource "google_project_iam_member" "cicd_ar_writer" {
  project = var.project
  role    = "roles/artifactregistry.writer"
  member  = "serviceAccount:${google_service_account.cicd.email}"
}

# Allow the SA to deploy new Cloud Run revisions.
resource "google_project_iam_member" "cicd_run_developer" {
  project = var.project
  role    = "roles/run.developer"
  member  = "serviceAccount:${google_service_account.cicd.email}"
}

# Allow the SA to act as the Cloud Run runtime SA when updating services
# (required by terraform apply of cloudrun.tf via the cicd SA).
resource "google_service_account_iam_member" "cicd_act_as_platform_sa" {
  service_account_id = google_service_account.platform.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.cicd.email}"
}

# Allow GCS access so the cicd SA can read/write Terraform state during apply.
resource "google_project_iam_member" "cicd_storage_admin" {
  project = var.project
  role    = "roles/storage.objectAdmin"
  member  = "serviceAccount:${google_service_account.cicd.email}"
}

# ---------------------------------------------------------------------------
# Bind the GitHub Actions WIF identity to the CI/CD SA
# ---------------------------------------------------------------------------

# Any workflow running from the pmk6vc/validation-platform repository can
# impersonate validation-cicd-sa.  The attribute_condition on the provider
# already limits tokens to that repo; this binding further scopes to the
# principalSet for that repo (defense in depth).
resource "google_service_account_iam_member" "cicd_wif_binding" {
  service_account_id = google_service_account.cicd.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github_actions.name}/attribute.repository/pmk6vc/validation-platform"
}
