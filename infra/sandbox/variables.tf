variable "project" {
  description = "GCP project ID"
  type        = string
  default     = "zugzwang-381922"
}

variable "region" {
  description = "GCP region for the sandbox cluster"
  type        = string
  default     = "us-central1"
}

variable "node_count" {
  description = "Number of nodes in the sandbox node pool"
  type        = number
  default     = 2
}
