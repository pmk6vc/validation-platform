# GKE Standard cluster for test microservices and future validation agent.
# Spot (preemptible) e2-small nodes keep costs low (~$80/mo when running).
# Workload Identity is enabled so pods can authenticate to GCP APIs without key files.

resource "google_container_cluster" "sandbox" {
  name     = "validation-sandbox"
  location = var.region
  project  = var.project

  # Remove the default node pool immediately; we manage our own.
  remove_default_node_pool = true
  initial_node_count       = 1

  workload_identity_config {
    workload_pool = "${var.project}.svc.id.goog"
  }

  # Logging and monitoring via Cloud Operations (formerly Stackdriver).
  logging_service    = "logging.googleapis.com/kubernetes"
  monitoring_service = "monitoring.googleapis.com/kubernetes"

  deletion_protection = false
}

resource "google_container_node_pool" "spot" {
  name     = "spot-pool"
  cluster  = google_container_cluster.sandbox.name
  location = var.region
  project  = var.project

  node_count = var.node_count

  node_config {
    machine_type = "e2-small"
    spot         = true

    # Minimal OAuth scopes — workloads use Workload Identity, not the node SA.
    oauth_scopes = [
      "https://www.googleapis.com/auth/cloud-platform",
    ]

    workload_metadata_config {
      mode = "GKE_METADATA"
    }
  }

  management {
    auto_repair  = true
    auto_upgrade = true
  }
}
