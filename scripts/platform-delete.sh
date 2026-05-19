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
require_cmd cloud-sql-proxy
require_cmd psql
require_cmd openssl
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
# Booting a stopped instance + flipping a flag in one patch can exceed
# gcloud's default sync-wait window. Dispatch async and wait explicitly
# with a 30-minute timeout — the underlying instance start can take
# several minutes and any sync-wait timeout fails the script even though
# the API operation is still progressing.
OP_NAME="$(gcloud sql instances patch validation-postgres \
  --activation-policy=ALWAYS \
  --no-deletion-protection \
  --project="${PROJECT}" \
  --async \
  --format="value(name)" \
  --quiet)"
info "Patch operation queued: ${OP_NAME}. Waiting (up to 30 minutes)..."
gcloud sql operations wait "${OP_NAME}" \
  --project="${PROJECT}" \
  --timeout=1800 \
  --quiet
success "Instance running and deletion protection disabled."

# ---------------------------------------------------------------------------
# Step 2 — Apply current config to flip Cloud Run deletion_protection to false
#
# Cloud Run services in GCP have deletion_protection=true from a prior apply
# (it was the provider default). The current Terraform config now sets it to
# false, but until we apply that, terraform destroy refuses with
# "cannot destroy service without setting deletion_protection=false and
# running terraform apply". This step reconciles state before destroy.
# ---------------------------------------------------------------------------

info "Reconciling Cloud Run deletion_protection state before destroy..."
terraform -chdir="${REPO_ROOT}/infra/platform" apply \
  -auto-approve \
  -var="cloudsql_active=true" \
  -var="cloudsql_deletion_protection=false" \
  -var="platform_image=${PLACEHOLDER_IMAGE}" \
  -var="collector_image=${PLACEHOLDER_IMAGE}"

# ---------------------------------------------------------------------------
# Step 3 — Strip Postgres-level ownership and grants from IAM SQL users
#
# terraform destroy tears down google_sql_user resources, which translates to
# DROP ROLE in Postgres. Postgres refuses to drop a role that owns any
# objects (Flyway-created tables are owned by the platform SA) or that holds
# grants on objects owned by others (bootstrap-db.sh did
# `GRANT ALL ON SCHEMA public TO <platform-sa>`). Without this step,
# terraform destroy errors out with:
#   role "validation-platform-sa@<project>.iam" cannot be dropped because
#   some objects depend on it
#
# Mirrors the bootstrap-db.sh pattern: set a random postgres password for
# ~seconds, drive psql through cloud-sql-proxy, then rotate the password
# back to an unknown value. For every IAM user the instance has, run:
#   GRANT <role> TO postgres        -- ensure postgres can REASSIGN
#   REASSIGN OWNED BY <role> TO postgres   -- transfer table ownership
#   DROP OWNED BY <role> CASCADE    -- drop residue + revoke privileges
#   REVOKE <role> FROM postgres     -- restore membership state
# ---------------------------------------------------------------------------

info "Listing IAM SQL users on the instance..."
# Portable bash 3.2 array-from-stream (mapfile is bash 4+; macOS ships 3.2).
IAM_USERS=()
while IFS= read -r _user; do
  [[ -n "${_user}" ]] && IAM_USERS+=("${_user}")
done < <(
  gcloud sql users list \
    --instance=validation-postgres \
    --project="${PROJECT}" \
    --filter="type=CLOUD_IAM_USER OR type=CLOUD_IAM_SERVICE_ACCOUNT" \
    --format="value(name)" 2>/dev/null
)

if [[ ${#IAM_USERS[@]} -eq 0 ]]; then
  info "  No IAM users found — nothing to clean."
else
  # See common.sh::strip_postgres_ownership_for_roles for mechanics.
  strip_postgres_ownership_for_roles "${IAM_USERS[@]}"
fi

# ---------------------------------------------------------------------------
# Step 4 — Destroy the platform stack
#
# We pass placeholder images because platform_image and collector_image are
# required variables with no default. Terraform destroy does not actually
# deploy anything, but the variable validation still runs before the plan.
# ---------------------------------------------------------------------------

info "Running terraform destroy on the platform stack..."
terraform -chdir="${REPO_ROOT}/infra/platform" destroy \
  -auto-approve \
  -var="cloudsql_active=true" \
  -var="cloudsql_deletion_protection=false" \
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
