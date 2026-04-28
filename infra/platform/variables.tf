variable "project" {
  description = "GCP project ID"
  type        = string
  default     = "zugzwang-381922"
}

variable "region" {
  description = "GCP region for all resources"
  type        = string
  default     = "us-central1"
}

variable "cloudsql_active" {
  description = "When true Cloud SQL runs continuously (ALWAYS); set false to pause the instance and reduce costs"
  type        = bool
  default     = true
}

variable "cloudsql_deletion_protection" {
  description = "When true the Cloud SQL instance cannot be destroyed. platform-delete.sh sets this false to allow the nuclear destroy path."
  type        = bool
  default     = true
}

variable "platform_image" {
  description = "Container image for the validation-platform Cloud Run service. Required — set via -var, e.g. us-central1-docker.pkg.dev/zugzwang-381922/validation/platform:<sha>"
  type        = string
}

variable "collector_image" {
  description = "Container image for the validation-collector Cloud Run service. Required — set via -var."
  type        = string
}
