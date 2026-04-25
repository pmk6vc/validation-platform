#!/usr/bin/env bash
# sandbox-up.sh — Bring the sandbox GKE cluster up via Terraform.
#
# The sandbox stack costs ~$80/mo when running. Only apply when you need it.
# Destroy it when not in use with sandbox-down.sh.
#
# Usage:
#   ./scripts/sandbox-up.sh
#
# Environment overrides:
#   PROJECT    — GCP project ID (default: zugzwang-381922)
#   REGION     — GCP region     (default: us-central1)
#   NODE_COUNT — number of nodes in the sandbox node pool (default: Terraform variable default)

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
# Optional: node count override
# ---------------------------------------------------------------------------

EXTRA_VARS=()
if [[ -n "${NODE_COUNT:-}" ]]; then
  EXTRA_VARS+=(-var="node_count=${NODE_COUNT}")
  info "Using NODE_COUNT override: ${NODE_COUNT}"
fi

# ---------------------------------------------------------------------------
# Apply
# ---------------------------------------------------------------------------

info "Applying sandbox stack (~\$80/mo while running)..."
terraform -chdir="${REPO_ROOT}/infra/sandbox" apply \
  -auto-approve \
  "${EXTRA_VARS[@]}"

# ---------------------------------------------------------------------------
# Print kubeconfig fetch command and next steps
# ---------------------------------------------------------------------------

CLUSTER_NAME="$(terraform -chdir="${REPO_ROOT}/infra/sandbox" output -raw cluster_name)"
CLUSTER_LOCATION="$(terraform -chdir="${REPO_ROOT}/infra/sandbox" output -raw cluster_location)"

echo ""
success "Sandbox cluster is up."
echo ""
echo "Fetch kubeconfig:"
echo "  gcloud container clusters get-credentials ${CLUSTER_NAME} \\"
echo "    --region=${CLUSTER_LOCATION} \\"
echo "    --project=${PROJECT}"
echo ""
echo "To destroy the sandbox and stop all charges:"
echo "  ./scripts/sandbox-down.sh"
echo ""
