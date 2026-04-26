variable "project" {
  description = "GCP project ID"
  type        = string
  default     = "zugzwang-381922"
}

variable "region" {
  description = "GCP region (informational; the sandbox is zonal — see var.zone)"
  type        = string
  default     = "us-central1"
}

variable "zone" {
  description = "GCP zone for the (zonal) sandbox cluster"
  type        = string
  default     = "us-central1-a"
}

variable "node_count" {
  description = "Number of nodes in the sandbox node pool (zonal cluster: this is the actual total)"
  type        = number
  default     = 1
}
