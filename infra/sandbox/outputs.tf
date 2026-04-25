output "cluster_name" {
  description = "GKE cluster name"
  value       = google_container_cluster.sandbox.name
}

output "cluster_location" {
  description = "GCP region where the sandbox cluster is deployed"
  value       = google_container_cluster.sandbox.location
}

output "cluster_endpoint" {
  description = "GKE cluster API server endpoint"
  value       = google_container_cluster.sandbox.endpoint
  sensitive   = true
}
