#!/usr/bin/env bash
# sandbox-up.sh — Bring the sandbox GKE cluster up via Terraform and deploy
# the test microservices.
#
# The sandbox stack costs ~$80/mo when running. Only apply when you need it.
# Destroy it when not in use with sandbox-down.sh.
#
# Usage:
#   ./scripts/sandbox-up.sh                # default: pull :latest from Artifact Registry
#   ./scripts/sandbox-up.sh --build-local  # build+push from local source, deploy that
#
# Default mode (pull :latest):
#   - Deterministic: cluster runs whatever CI (push_main.yml) last pushed
#   - Fast: no local build
#   - Requires CI to have pushed images at least once (after a main merge)
#
# --build-local mode:
#   - Builds each test-service image from the current working directory
#   - Tags with :dev-<git-sha>(-dirty) — does NOT touch :latest (no CI race)
#   - Updates the Deployments via `kubectl set image` to roll the dev tag
#   - Use this when iterating on test-services code, or for first-time setup
#     before CI has pushed any images
#
# Environment overrides:
#   PROJECT    — GCP project ID (default: zugzwang-381922)
#   REGION     — GCP region     (default: us-central1)
#   NODE_COUNT — number of nodes in the sandbox node pool

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/common.sh
source "${SCRIPT_DIR}/common.sh"

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------

BUILD_LOCAL=false
for arg in "$@"; do
  case "${arg}" in
    --build-local) BUILD_LOCAL=true ;;
    -h | --help)
      sed -n '2,30p' "${BASH_SOURCE[0]}"
      exit 0
      ;;
    *)
      die "Unknown argument: ${arg}"
      ;;
  esac
done

TEST_SERVICES=(api-gateway order-service notification-service traffic-generator webhook-stub)

# ---------------------------------------------------------------------------
# Prerequisites
# ---------------------------------------------------------------------------

require_cmd terraform
require_cmd kubectl
check_gcloud
if [[ "${BUILD_LOCAL}" == "true" ]]; then
  require_cmd docker
  require_cmd git
fi

# ---------------------------------------------------------------------------
# Optional: node count override
# ---------------------------------------------------------------------------

EXTRA_VARS=()
if [[ -n "${NODE_COUNT:-}" ]]; then
  EXTRA_VARS+=(-var="node_count=${NODE_COUNT}")
  info "Using NODE_COUNT override: ${NODE_COUNT}"
fi

# ---------------------------------------------------------------------------
# Apply Terraform (creates / updates the GKE cluster)
# ---------------------------------------------------------------------------

info "Applying sandbox stack (~\$80/mo while running)..."
terraform -chdir="${REPO_ROOT}/infra/sandbox" apply \
  -auto-approve \
  ${EXTRA_VARS[@]+"${EXTRA_VARS[@]}"}

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
# Optional: build + push dev images BEFORE applying the cluster manifests
# ---------------------------------------------------------------------------
#
# Default (no --build-local): images come from Artifact Registry's :latest
# tag, which CI (push_main.yml) keeps fresh on every merge to main. The
# cluster runs whatever CI last pushed — deterministic and cheap.
#
# --build-local: tag with :dev-<sha>(-dirty) instead of :latest so we never
# clobber what CI pushed. Two devs running --build-local concurrently each
# get their own tag (different SHAs / dirty flag), no race for :latest.

DEV_TAG=""
if [[ "${BUILD_LOCAL}" == "true" ]]; then
  GIT_SHA="$(git -C "${REPO_ROOT}" rev-parse --short HEAD)"
  if [[ -n "$(git -C "${REPO_ROOT}" status --porcelain)" ]]; then
    DEV_TAG="dev-${GIT_SHA}-dirty"
  else
    DEV_TAG="dev-${GIT_SHA}"
  fi
  info "Building local sources as :${DEV_TAG} (NOT touching :latest)"

  info "Configuring Docker for Artifact Registry..."
  gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet

  ACCESS_TOKEN="$(gcloud auth print-access-token)"
  for svc in "${TEST_SERVICES[@]}"; do
    image="${REGISTRY}/test-${svc}"
    info "  Pushing ${image}:${DEV_TAG}"
    "${REPO_ROOT}/gradlew" ":test-services:${svc}:jib" \
      -p "${REPO_ROOT}" \
      -Djib.to.image="${image}" \
      -Djib.to.tags="${DEV_TAG}" \
      -Djib.to.auth.username=oauth2accesstoken \
      -Djib.to.auth.password="${ACCESS_TOKEN}" \
      -Djib.arch=amd64 \
      --quiet
  done
fi

# ---------------------------------------------------------------------------
# Deploy test services via Kustomize
# ---------------------------------------------------------------------------
#
# The overlay's images point at :latest by default. If --build-local pushed a
# :dev-<sha> tag, we apply :latest first (creating the Deployments with the
# right service account / volumes / etc) then `kubectl set image` to roll the
# dev tag. This avoids forking the kustomization file or shelling out to
# `kustomize edit`.

info "Applying test-service manifests (k8s/test-services/overlays/gke)..."
kubectl apply -k "${REPO_ROOT}/k8s/test-services/overlays/gke"

if [[ -n "${DEV_TAG}" ]]; then
  info "Rolling Deployments to :${DEV_TAG}..."
  # api-gateway, order-service, notification-service, traffic-generator → namespace `production`
  # webhook-stub → namespace `external`
  declare -A SVC_NS=(
    [api-gateway]=production
    [order-service]=production
    [notification-service]=production
    [traffic-generator]=production
    [webhook-stub]=external
  )
  for svc in "${TEST_SERVICES[@]}"; do
    ns="${SVC_NS[$svc]}"
    image="${REGISTRY}/test-${svc}:${DEV_TAG}"
    kubectl set image "deployment/${svc}" "${svc}=${image}" --namespace="${ns}"
  done
fi

info "Waiting for Deployments to become available (timeout 120s)..."
for ns in infrastructure production external; do
  kubectl wait deployment \
    --all \
    --for=condition=available \
    --timeout=120s \
    --namespace="${ns}" 2>/dev/null || true
done

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

echo ""
if [[ -n "${DEV_TAG}" ]]; then
  success "Sandbox cluster is up with test services deployed (local build :${DEV_TAG})."
else
  success "Sandbox cluster is up with test services deployed (Artifact Registry :latest)."
fi
echo ""
echo "Inspect the cluster:"
echo "  kubectl get pods -A"
echo ""
echo "Iterate on local test-services code:"
echo "  ./scripts/sandbox-up.sh --build-local"
echo ""
echo "To destroy the sandbox and stop all charges:"
echo "  ./scripts/sandbox-down.sh"
echo ""
