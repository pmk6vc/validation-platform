#!/usr/bin/env bash
# platform-delete.sh — NUCLEAR: destroy the entire platform stack.
#
# WARNING: This permanently destroys Cloud SQL (all data), Cloud Run services,
#          Artifact Registry, Secret Manager resources, and IAM bindings.
#          This action CANNOT be undone.
#
# State buckets (gs://...-terraform-state-platform) are NOT deleted.
# They are created by bootstrap and must be removed manually if desired.
#
# Usage:
#   ./scripts/platform-delete.sh
#
# Environment overrides:
#   PROJECT   — GCP project ID (default: zugzwang-381922)
#   REGION    — GCP region     (default: us-central1)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/common.sh
source "${SCRIPT_DIR}/common.sh"

# ANSI bold/reset — only used in the warning banner
BOLD=$'\033[1m'
RESET=$'\033[0m'

# ---------------------------------------------------------------------------
# Prerequisites
# ---------------------------------------------------------------------------

require_cmd terraform
check_gcloud

# ---------------------------------------------------------------------------
# Confirmation gate — user must type the project ID
# ---------------------------------------------------------------------------

echo ""
echo "${BOLD}!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!${RESET}"
echo "${BOLD}  WARNING — NUCLEAR OPERATION                                      ${RESET}"
echo "${BOLD}!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!${RESET}"
echo ""
echo "  This will PERMANENTLY destroy:"
echo "    - Cloud SQL instance (validation-postgres) and ALL data"
echo "    - Cloud Run services (validation-platform, validation-collector)"
echo "    - Artifact Registry repository (validation)"
echo "    - Secret Manager resources (db password, JWT key)"
echo "    - IAM service accounts and bindings"
echo ""
echo "  State buckets are NOT deleted (remove manually if desired)."
echo ""
echo "  Type the project ID to confirm: ${PROJECT}"
echo ""
read -r -p "  Project ID: " CONFIRMATION

if [[ "${CONFIRMATION}" != "${PROJECT}" ]]; then
  echo ""
  warn "Confirmation did not match '${PROJECT}'. Aborted."
  exit 1
fi

echo ""
info "Confirmation accepted. Proceeding with destruction..."

# ---------------------------------------------------------------------------
# Step 1 — Start the instance and disable Cloud SQL deletion protection
#
# Cloud SQL has deletion_protection=true in Terraform.
# Terraform destroy will fail unless we flip it first.
# Cloud SQL also rejects patches to any other property while the instance is
# stopped (activation_policy=NEVER, set by platform-down.sh) UNLESS the same
# patch operation also starts the instance. So we set activation_policy=ALWAYS
# in the same call.
# ---------------------------------------------------------------------------

info "Starting instance and disabling Cloud SQL deletion protection on 'validation-postgres'..."
gcloud sql instances patch validation-postgres \
  --activation-policy=ALWAYS \
  --no-deletion-protection \
  --project="${PROJECT}" \
  --quiet
success "Instance running and deletion protection disabled."

# ---------------------------------------------------------------------------
# Step 2 — Destroy the platform stack
#
# We pass placeholder images because platform_image and collector_image are
# required variables with no default. Terraform destroy does not actually
# deploy anything, but the variable validation still runs before the plan.
# ---------------------------------------------------------------------------

info "Running terraform destroy on the platform stack..."
terraform -chdir="${REPO_ROOT}/infra/platform" destroy \
  -auto-approve \
  -var="cloudsql_active=true" \
  -var="platform_image=${PLACEHOLDER_IMAGE}" \
  -var="collector_image=${PLACEHOLDER_IMAGE}"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

echo ""
success "Platform stack destroyed. All resources have been deleted."
echo ""
echo "The Terraform state buckets remain:"
echo "  gs://${PROJECT}-terraform-state-platform"
echo "  gs://${PROJECT}-terraform-state-sandbox"
echo "Remove them manually if you no longer need them:"
echo "  gcloud storage rm -r gs://${PROJECT}-terraform-state-platform"
echo "  gcloud storage rm -r gs://${PROJECT}-terraform-state-sandbox"
echo ""
echo "To start fresh:"
echo "  ./scripts/bootstrap.sh"
echo ""
