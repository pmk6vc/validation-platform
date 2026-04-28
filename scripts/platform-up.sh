#!/usr/bin/env bash
# platform-up.sh — Bring the platform stack up (Cloud SQL active + Cloud Run).
#
# Usage:
#   ./scripts/platform-up.sh
#
# Image selection (in order of priority):
#   1. PLATFORM_IMAGE / COLLECTOR_IMAGE env vars (explicit override)
#   2. Latest tag in Artifact Registry (post-CI images)
#   3. Cloud Run hello placeholder (first-ever run before images exist)
#
# Environment overrides:
#   PROJECT          — GCP project ID (default: zugzwang-381922)
#   REGION           — GCP region     (default: us-central1)
#   PLATFORM_IMAGE   — full image URI for the platform Cloud Run service
#   COLLECTOR_IMAGE  — full image URI for the collector Cloud Run service

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/common.sh
source "${SCRIPT_DIR}/common.sh"

# ---------------------------------------------------------------------------
# Prerequisites
# ---------------------------------------------------------------------------

require_cmd terraform
check_gcloud

# ---------------------------------------------------------------------------
# Resolve images
# ---------------------------------------------------------------------------

if [[ -z "${PLATFORM_IMAGE:-}" ]]; then
  info "PLATFORM_IMAGE not set — resolving latest from Artifact Registry..."
  PLATFORM_IMAGE="$(latest_image platform)"
  info "Using platform image: ${PLATFORM_IMAGE}"
else
  info "Using PLATFORM_IMAGE override: ${PLATFORM_IMAGE}"
fi

if [[ -z "${COLLECTOR_IMAGE:-}" ]]; then
  info "COLLECTOR_IMAGE not set — resolving latest from Artifact Registry..."
  COLLECTOR_IMAGE="$(latest_image collector)"
  info "Using collector image: ${COLLECTOR_IMAGE}"
else
  info "Using COLLECTOR_IMAGE override: ${COLLECTOR_IMAGE}"
fi

# ---------------------------------------------------------------------------
# Apply
# ---------------------------------------------------------------------------

info "Applying platform stack (Cloud SQL active=true)..."
# terraform.tfvars (gitignored) supplies per-environment values like
# db_admin_users. terraform auto-loads it from the -chdir directory.
terraform -chdir="${REPO_ROOT}/infra/platform" apply \
  -auto-approve \
  -var="cloudsql_active=true" \
  -var="platform_image=${PLATFORM_IMAGE}" \
  -var="collector_image=${COLLECTOR_IMAGE}"

# ---------------------------------------------------------------------------
# Print outputs
# ---------------------------------------------------------------------------

echo ""
success "Platform stack is up."
echo ""
echo "Service URLs:"
terraform -chdir="${REPO_ROOT}/infra/platform" output -raw platform_service_url \
  | xargs -I{} echo "  Platform:  {}"
terraform -chdir="${REPO_ROOT}/infra/platform" output -raw collector_service_url \
  | xargs -I{} echo "  Collector: {}"
echo ""
echo "Artifact Registry:"
terraform -chdir="${REPO_ROOT}/infra/platform" output -raw artifact_registry_url \
  | xargs -I{} echo "  {}"
echo ""
echo "To pause Cloud SQL and save ~\$10/mo:"
echo "  ./scripts/platform-down.sh"
echo ""
