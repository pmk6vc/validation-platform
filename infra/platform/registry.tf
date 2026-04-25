# Artifact Registry — Docker repository for all platform container images.

resource "google_artifact_registry_repository" "validation" {
  location      = var.region
  repository_id = "validation"
  description   = "Container images for the validation platform"
  format        = "DOCKER"

  depends_on = [google_project_service.apis]
}
