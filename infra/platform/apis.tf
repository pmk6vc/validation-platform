# Enable all GCP APIs required by the platform stack.
# google_project_service is idempotent — re-running apply is a no-op when already enabled.
# disable_on_destroy = false prevents accidental API disablement when tearing down infra.

locals {
  required_apis = [
    "compute.googleapis.com",
    "container.googleapis.com",
    "sqladmin.googleapis.com",
    "secretmanager.googleapis.com",
    "artifactregistry.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "run.googleapis.com",
    "dns.googleapis.com",
  ]
}

resource "google_project_service" "apis" {
  for_each = toset(local.required_apis)

  project            = var.project
  service            = each.value
  disable_on_destroy = false
}
