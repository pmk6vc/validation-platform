#!/usr/bin/env bash
# sandbox-up.sh — Bring the sandbox GKE cluster up via Terraform and deploy
# the full demo: test microservices, Kubeshark, and the validation agent.
#
# The sandbox stack costs ~$80/mo when running. Only apply when you need it.
# Destroy it when not in use with sandbox-down.sh.
#
# What gets deployed (in order):
#   1. GKE cluster + node pool (Terraform)
#   2. Test microservices in `production` and `external` namespaces
#   3. Sandbox org + agent JWT (via seed-org.sh; writes K8s Secret)
#   4. Kubeshark (Helm; tap scoped to `production`)
#   5. validation-agent (overlay; URLs sed-substituted from Cloud Run TF outputs)
#
# Usage:
#   ./scripts/sandbox-up.sh                # default: pull :latest from Artifact Registry
#   ./scripts/sandbox-up.sh --build-local  # build+push from local source, deploy that
#
# Default mode (pull :latest):
#   - Deterministic: cluster runs whatever CI (push_main.yml) last pushed
#   - Fast: no local build
#   - Requires CI to have pushed images at least once (after a main merge);
#     CI builds platform, collector, all test-services, and validation-agent
#
# --build-local mode:
#   - Builds each test-service AND validation-agent image from the working dir
#   - Tags with :dev-<git-sha>(-dirty) — does NOT touch :latest (no CI race)
#   - Updates the Deployments via `kubectl set image` to roll the dev tag
#   - Use this when iterating on agent or test-services code, or for first-time
#     setup before CI has pushed any images
#
# Prerequisites:
#   terraform, kubectl, helm, gcloud (authenticated to ${PROJECT})
#   Plus seed-org.sh's prerequisites: curl, jq, uuidgen
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
require_cmd helm
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

  # The validation agent also ships as a Jib build. Push it under the same
  # :dev-<sha> tag so we can roll it onto the sandbox without touching :latest.
  agent_image="${REGISTRY}/validation-agent"
  info "  Pushing ${agent_image}:${DEV_TAG}"
  "${REPO_ROOT}/gradlew" ":agent:jib" \
    -p "${REPO_ROOT}" \
    -Djib.to.image="${agent_image}" \
    -Djib.to.tags="${DEV_TAG}" \
    -Djib.to.auth.username=oauth2accesstoken \
    -Djib.to.auth.password="${ACCESS_TOKEN}" \
    -Djib.arch=amd64 \
    --quiet
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
# Bootstrap sandbox org + agent JWT (Phase A of PLAN.md)
# ---------------------------------------------------------------------------
#
# seed-org.sh creates the sandbox org via the Cloud Run platform, mints an
# agent JWT, and writes it as a K8s Secret (`platform-api-key`) in the
# `validation` namespace. The agent's Deployment mounts that Secret, so the
# Secret must exist before the agent pod starts.

info "Ensuring 'validation' namespace exists for the agent's Secret..."
kubectl create namespace validation --dry-run=client -o yaml | kubectl apply -f -

info "Bootstrapping sandbox org and minting agent JWT..."
"${SCRIPT_DIR}/seed-org.sh"

# ---------------------------------------------------------------------------
# Install Kubeshark (scoped to the production namespace)
# ---------------------------------------------------------------------------
#
# Helm-managed install via the official Kubeshark chart. Tap is scoped to
# `production`, the namespace where the test microservices live — the only
# traffic worth capturing for the demo. Idempotent: skip if the front
# Deployment already exists.

if kubectl get deployment kubeshark-front -n kubeshark >/dev/null 2>&1; then
  info "Kubeshark already running in cluster (kubeshark-front exists); skipping install"
else
  info "Installing Kubeshark via Helm (scoped to production namespace)..."
  helm repo add kubeshark https://helm.kubeshark.co >/dev/null 2>&1 || true
  helm repo update kubeshark >/dev/null
  helm upgrade --install kubeshark kubeshark/kubeshark \
    --namespace kubeshark \
    --create-namespace \
    --set tap.namespaces='{production}'

  info "Waiting for kubeshark-front to become ready (timeout 180s)..."
  kubectl wait deployment kubeshark-front \
    --for=condition=available \
    --timeout=180s \
    --namespace=kubeshark
fi

# ---------------------------------------------------------------------------
# Deploy the validation agent
# ---------------------------------------------------------------------------
#
# k8s/agent/overlays/sandbox/ parameterizes PLATFORM_URL / COLLECTOR_URL /
# KUBESHARK_URL via __FOO__ placeholders. Read the Cloud Run URLs from
# Terraform outputs and substitute via sed before piping to kubectl apply.
# KUBESHARK_URL points at the in-cluster Service created by the Helm
# install above.

info "Reading Cloud Run URLs from Terraform platform outputs..."
PLATFORM_CLOUDRUN_URL="$(terraform -chdir="${REPO_ROOT}/infra/platform" output -raw platform_service_url)"
COLLECTOR_CLOUDRUN_URL="$(terraform -chdir="${REPO_ROOT}/infra/platform" output -raw collector_service_url)"
KUBESHARK_CLUSTER_URL="http://kubeshark-front.kubeshark:80"

info "Deploying validation-agent overlay..."
kubectl kustomize "${REPO_ROOT}/k8s/agent/overlays/sandbox" \
  | sed -e "s|__PLATFORM_URL__|${PLATFORM_CLOUDRUN_URL}|g" \
        -e "s|__COLLECTOR_URL__|${COLLECTOR_CLOUDRUN_URL}|g" \
        -e "s|__KUBESHARK_URL__|${KUBESHARK_CLUSTER_URL}|g" \
  | kubectl apply -f -

# In --build-local mode, roll the agent to the :dev-<sha> image. The overlay
# applies :latest by default — same pattern as the test-services rollout
# above (apply once to set up Deployment + RBAC, then `kubectl set image`).
if [[ -n "${DEV_TAG}" ]]; then
  info "Rolling validation-agent to :${DEV_TAG}..."
  kubectl set image deployment/validation-agent \
    "agent=${REGISTRY}/validation-agent:${DEV_TAG}" \
    --namespace=validation
fi

info "Waiting for validation-agent to become ready (timeout 120s)..."
kubectl wait deployment validation-agent \
  --for=condition=available \
  --timeout=120s \
  --namespace=validation \
  || warn "validation-agent did not reach Ready in time; check 'kubectl logs deployment/validation-agent -n validation'"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

echo ""
if [[ -n "${DEV_TAG}" ]]; then
  success "Sandbox is up — test services + Kubeshark + validation-agent deployed (local build :${DEV_TAG})."
else
  success "Sandbox is up — test services + Kubeshark + validation-agent deployed (Artifact Registry :latest)."
fi
echo ""
echo "Verify the end-to-end flow:"
echo "  kubectl logs deployment/validation-agent -n validation -f"
echo "  # Look for: 'Registered service production/...' followed by 'Captured N entries'"
echo ""
echo "Inspect the cluster:"
echo "  kubectl get pods -A"
echo ""
echo "Iterate on local source (test-services + agent):"
echo "  ./scripts/sandbox-up.sh --build-local"
echo ""
echo "To destroy the sandbox and stop all charges:"
echo "  ./scripts/sandbox-down.sh"
echo ""
