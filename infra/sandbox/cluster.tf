# GKE Standard cluster for test microservices and future validation agent.
# Spot (preemptible) e2-small nodes keep costs low (~$80/mo when running).
# Workload Identity is enabled so pods can authenticate to GCP APIs without key files.

resource "google_container_cluster" "sandbox" {
  name = "validation-sandbox"
  # Zonal (single zone) instead of regional. The sandbox is dev-only — we
  # don't need multi-zone HA. Zonal clusters provision faster (~3-5 min vs
  # ~10 min) and the first zonal cluster per billing account is free
  # (regional clusters always pay the $73/mo management fee).
  location = var.zone
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
  location = var.zone
  project  = var.project

  node_count = var.node_count

  node_config {
    # e2-standard-2 (2 dedicated vCPU / 8 GiB) is the smallest E2 type with
    # non-shared cores. The shared-core E2 family (e2-micro/small/medium) all
    # cap GKE allocatable CPU at ~940m, of which system pods consume ~931m,
    # leaving nothing for test workloads — pods stayed Pending with
    # `Insufficient cpu` and the non-autoscaled pool logged NotTriggerScaleUp.
    # Dedicated cores give ~1930m allocatable, enough headroom for the
    # ~670m of test microservices on a single spot node.
    machine_type = "e2-standard-2"
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
