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
require_cmd kubectl
require_cmd docker
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
  ${EXTRA_VARS[@]+"${EXTRA_VARS[@]}"}

# ---------------------------------------------------------------------------
# Print kubeconfig fetch command and next steps
# ---------------------------------------------------------------------------

CLUSTER_NAME="$(terraform -chdir="${REPO_ROOT}/infra/sandbox" output -raw cluster_name)"
CLUSTER_LOCATION="$(terraform -chdir="${REPO_ROOT}/infra/sandbox" output -raw cluster_location)"

# ---------------------------------------------------------------------------
# Fetch kubeconfig
# ---------------------------------------------------------------------------

info "Fetching kubeconfig for cluster ${CLUSTER_NAME}..."
gcloud container clusters get-credentials "${CLUSTER_NAME}" \
  --region="${CLUSTER_LOCATION}" \
  --project="${PROJECT}"

# ---------------------------------------------------------------------------
# Build and push test-service images
# ---------------------------------------------------------------------------
# Images are pushed here so the first sandbox-up works even before any CI
# run has pushed them. CI (push_main.yml) keeps them current on every merge.

info "Configuring Docker for Artifact Registry..."
gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet

info "Building and pushing test-service images to Artifact Registry..."
ACCESS_TOKEN="$(gcloud auth print-access-token)"
for svc in api-gateway order-service notification-service traffic-generator webhook-stub; do
  image="${REGISTRY}/test-${svc}"
  info "  Pushing ${image}:latest"
  "${REPO_ROOT}/gradlew" ":test-services:${svc}:jib" \
    -p "${REPO_ROOT}" \
    -Djib.to.image="${image}" \
    -Djib.to.tags=latest \
    -Djib.to.auth.username=oauth2accesstoken \
    -Djib.to.auth.password="${ACCESS_TOKEN}" \
    -Djib.arch=amd64 \
    --quiet
done

# ---------------------------------------------------------------------------
# Deploy test services via Kustomize
# ---------------------------------------------------------------------------

info "Applying test-service manifests (k8s/test-services/overlays/gke)..."
kubectl apply -k "${REPO_ROOT}/k8s/test-services/overlays/gke"

info "Waiting for Deployments to become available (timeout 120s)..."
for ns in infrastructure production external; do
  kubectl wait deployment \
    --all \
    --for=condition=available \
    --timeout=120s \
    --namespace="${ns}" 2>/dev/null || true
done

echo ""
success "Sandbox cluster is up with test services deployed."
echo ""
echo "Inspect the cluster:"
echo "  kubectl get pods -A"
echo ""
echo "To destroy the sandbox and stop all charges:"
echo "  ./scripts/sandbox-down.sh"
echo ""
