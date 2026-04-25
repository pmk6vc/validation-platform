#!/usr/bin/env bash
# create-state-buckets.sh
# One-time bootstrap: create GCS buckets for Terraform remote state.
# Idempotent — safe to run multiple times.
#
# Usage:
#   ./infra/bootstrap/create-state-buckets.sh
#
# Prerequisites:
#   - gcloud CLI authenticated with an account that has storage.buckets.create
#   - PROJECT env var can override the default project

set -euo pipefail

PROJECT="${PROJECT:-zugzwang-381922}"
REGION="${REGION:-us-central1}"

PLATFORM_BUCKET="gs://${PROJECT}-terraform-state-platform"
SANDBOX_BUCKET="gs://${PROJECT}-terraform-state-sandbox"

create_bucket_if_missing() {
  local bucket="$1"
  if gcloud storage buckets describe "${bucket}" --project="${PROJECT}" &>/dev/null; then
    echo "Bucket ${bucket} already exists — skipping creation."
  else
    echo "Creating bucket ${bucket} ..."
    gcloud storage buckets create "${bucket}" \
      --project="${PROJECT}" \
      --location="${REGION}" \
      --uniform-bucket-level-access
    echo "Enabling versioning on ${bucket} ..."
    gcloud storage buckets update "${bucket}" --versioning
    echo "Bucket ${bucket} created with versioning enabled."
  fi
}

echo "=== Terraform State Bootstrap ==="
echo "Project : ${PROJECT}"
echo "Region  : ${REGION}"
echo ""

create_bucket_if_missing "${PLATFORM_BUCKET}"
create_bucket_if_missing "${SANDBOX_BUCKET}"

echo ""
echo "Done. You can now run 'terraform init' in infra/platform and infra/sandbox."
