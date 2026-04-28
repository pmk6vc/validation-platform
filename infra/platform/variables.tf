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

variable "db_admin_users" {
  description = <<-EOT
    Engineer email addresses granted cloudsqlsuperuser via Cloud SQL IAM auth.
    Each becomes a CLOUD_IAM_USER in the validation-postgres instance and gets
    roles/cloudsql.instanceUser at the project level. The actual cloudsqlsuperuser
    grant is applied in-DB by scripts/bootstrap-db.sh (one-time per DB lifetime).
    Set the concrete list per environment in terraform.tfvars (gitignored).
  EOT
  type        = set(string)
  default     = []
}
