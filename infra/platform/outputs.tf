output "cloudsql_connection_name" {
  description = "Cloud SQL instance connection name (used to configure Cloud SQL Auth Proxy)"
  value       = google_sql_database_instance.postgres.connection_name
}

output "artifact_registry_url" {
  description = "Artifact Registry repository URL (push images here)"
  value       = "${var.region}-docker.pkg.dev/${var.project}/${google_artifact_registry_repository.validation.repository_id}"
}

output "platform_service_url" {
  description = "Cloud Run URL for the validation-platform service"
  value       = google_cloud_run_v2_service.platform.uri
}

output "collector_service_url" {
  description = "Cloud Run URL for the validation-collector service"
  value       = google_cloud_run_v2_service.collector.uri
}

output "platform_service_account_email" {
  description = "Email of the validation-platform-sa service account (used by Cloud Run services)"
  value       = google_service_account.platform.email
}

output "sandbox_service_account_email" {
  description = "Email of the validation-sandbox-sa service account (used by GKE Workload Identity)"
  value       = google_service_account.sandbox.email
}
