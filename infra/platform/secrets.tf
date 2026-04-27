# Secret Manager — creates the secret resources.
# Secret VALUES are not stored in Terraform. Populate manually:
#
#   gcloud secrets versions add validation-jwt-private-key \
#     --project=zugzwang-381922 --data-file=path/to/private_key.pem
#
# Note: validation-db-password used to live here too. Cloud Run now
# authenticates to Cloud SQL via IAM (DATABASE_AUTH_MODE=iam) so no DB
# password exists in production. Local/docker still use a literal
# DATABASE_PASSWORD env var pointing at their own Postgres containers.

resource "google_secret_manager_secret" "jwt_private_key" {
  secret_id = "validation-jwt-private-key"
  project   = var.project

  replication {
    auto {}
  }

  depends_on = [google_project_service.apis]
}
