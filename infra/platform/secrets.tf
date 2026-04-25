# Secret Manager — creates the secret resources.
# Secret VALUES are not stored in Terraform. Populate them manually:
#
#   echo -n "your-db-password" | gcloud secrets versions add validation-db-password \
#     --project=zugzwang-381922 --data-file=-
#
#   gcloud secrets versions add validation-jwt-private-key \
#     --project=zugzwang-381922 --data-file=path/to/private_key.pem

resource "google_secret_manager_secret" "db_password" {
  secret_id = "validation-db-password"
  project   = var.project

  replication {
    auto {}
  }

  depends_on = [google_project_service.apis]
}

resource "google_secret_manager_secret" "jwt_private_key" {
  secret_id = "validation-jwt-private-key"
  project   = var.project

  replication {
    auto {}
  }

  depends_on = [google_project_service.apis]
}
