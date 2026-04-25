#!/usr/bin/env bash
# sandbox-down.sh — Destroy the sandbox GKE cluster via Terraform.
#
# The sandbox cluster has no persistent data — destroying it is the correct
# way to stop charges. Recreate it at any time with sandbox-up.sh.
#
# Usage:
#   ./scripts/sandbox-down.sh
#
# Environment overrides:
#   PROJECT   — GCP project ID (default: zugzwang-381922)
#   REGION    — GCP region     (default: us-central1)

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
# Destroy
# ---------------------------------------------------------------------------

info "Destroying sandbox stack (GKE cluster + node pool)..."
terraform -chdir="${REPO_ROOT}/infra/sandbox" destroy \
  -auto-approve

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

echo ""
success "Sandbox cluster destroyed. All compute charges stopped."
echo ""
echo "To recreate the cluster:"
echo "  ./scripts/sandbox-up.sh"
echo ""
