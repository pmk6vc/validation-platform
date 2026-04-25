#!/usr/bin/env bash
# bootstrap.sh — First-time setup for the validation platform on GCP.
# Idempotent — safe to run multiple times.
#
# Usage:
#   ./scripts/bootstrap.sh
#
# What it does:
#   1. Verifies gcloud auth and active project
#   2. Enables required GCP APIs
#   3. Creates Terraform state buckets (idempotent)
#   4. Runs `terraform init` for both stacks
#
# What it does NOT do:
#   - It does not run `terraform apply`. Use platform-up.sh for that.
#   - It does not populate Secret Manager values. Do that manually after
#     platform-up.sh succeeds (see instructions printed at the end).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/common.sh
source "${SCRIPT_DIR}/common.sh"

# ---------------------------------------------------------------------------
# Step 1 — Prerequisites
# ---------------------------------------------------------------------------

info "Checking prerequisites..."
require_cmd terraform
check_gcloud
success "gcloud is authenticated. Active project: ${PROJECT}"

# ---------------------------------------------------------------------------
# Step 2 — Enable required GCP APIs
# ---------------------------------------------------------------------------

info "Enabling required GCP APIs (this may take a minute on first run)..."
gcloud services enable \
  compute.googleapis.com \
  container.googleapis.com \
  sqladmin.googleapis.com \
  secretmanager.googleapis.com \
  artifactregistry.googleapis.com \
  iam.googleapis.com \
  iamcredentials.googleapis.com \
  run.googleapis.com \
  dns.googleapis.com \
  --project="${PROJECT}"
success "GCP APIs enabled."

# ---------------------------------------------------------------------------
# Step 3 — Create Terraform state buckets
# ---------------------------------------------------------------------------

info "Creating Terraform state buckets (idempotent)..."
bash "${REPO_ROOT}/infra/bootstrap/create-state-buckets.sh"

# ---------------------------------------------------------------------------
# Step 4 — terraform init for both stacks
# ---------------------------------------------------------------------------

info "Initialising Terraform — platform stack..."
terraform -chdir="${REPO_ROOT}/infra/platform" init
success "platform stack initialised."

info "Initialising Terraform — sandbox stack..."
terraform -chdir="${REPO_ROOT}/infra/sandbox" init
success "sandbox stack initialised."

# ---------------------------------------------------------------------------
# Next steps
# ---------------------------------------------------------------------------

echo ""
echo "========================================================"
echo "  Bootstrap complete."
echo "========================================================"
echo ""
echo "Next steps:"
echo ""
echo "  1. Bring the platform up (Cloud SQL + Cloud Run):"
echo "       ./scripts/platform-up.sh"
echo ""
echo "  2. After platform-up succeeds, populate Secret Manager:"
echo ""
echo "     # Database password:"
echo "     echo -n 'your-db-password' | gcloud secrets versions add validation-db-password \\"
echo "       --project=${PROJECT} --data-file=-"
echo ""
echo "     # JWT private key (RSA PEM):"
echo "     gcloud secrets versions add validation-jwt-private-key \\"
echo "       --project=${PROJECT} --data-file=path/to/private_key.pem"
echo ""
echo "  3. Optionally bring the sandbox GKE cluster up:"
echo "       ./scripts/sandbox-up.sh"
echo ""
