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

# Developer Google accounts that can connect to Cloud SQL via the Cloud SQL
# Auth Proxy and an IAM-issued OAuth token (no static password). Each entry
# becomes a CLOUD_IAM_USER on the instance and gets the project-level roles
# needed to (a) reach the proxy and (b) authenticate to Postgres.
#
# NOT PRODUCTION-GRADE for several reasons, all called out in the PR that
# introduced this:
#   - Standing access (no JIT / time-bound expiry).
#   - Personal Google accounts (gmail.com) have weaker controls than
#     Workspace identities — no org-enforced MFA / SSO / conditional access /
#     offboarding.
#   - No Postgres-level role separation: an IAM user added here can be
#     granted any privileges (read, write, DDL) once they connect. Default
#     PG behavior is read-nothing until the schema owner explicitly GRANTs.
#
# For prod, replace with: Workspace identities, IAM Conditions for expiry,
# a separate read-only Postgres role mapped to the IAM user via GRANT, and
# Cloud Audit Logs piped to a SIEM.
variable "dev_db_users" {
  description = "Google account emails that get direct Cloud SQL access via the Auth Proxy + IAM auth. Sandbox/dev convenience only — see comment for prod limitations."
  type        = list(string)
  default     = []
}
