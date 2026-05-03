#!/usr/bin/env bash
# seed-org.sh — Bootstrap the sandbox org in the deployed Cloud Run platform,
# mint an agent JWT, and write it as a Kubernetes Secret in the sandbox cluster.
#
# This is Phase A of PLAN.md: the agent can't push captured traffic without
# a JWT, and the JWT requires an organization to exist. One small script
# bootstraps both.
#
# Usage:
#   ./scripts/seed-org.sh
#
# Prerequisites:
#   - gcloud authenticated as a project member with:
#       * roles/secretmanager.secretAccessor on validation-jwt-private-key
#       * roles/container.developer (to fetch sandbox cluster credentials)
#   - terraform (reads platform_service_url + sandbox cluster_name/_location)
#   - kubectl
#   - curl, jq, uuidgen
#   - The Cloud Run platform service must be reachable (run platform-up.sh first).
#   - The sandbox GKE cluster must exist (run sandbox-up.sh first).
#
# Environment overrides:
#   PROJECT           — GCP project ID         (default: from common.sh)
#   PLATFORM_URL      — Cloud Run platform URL  (default: read from Terraform output)
#   SANDBOX_ORG_NAME  — org name to create      (default: sandbox-org)
#   CLUSTER_CLAIM     — agent JWT cluster claim (default: validation-sandbox)
#
# Idempotency:
#   The org ID is cached locally in .platform/sandbox-org-id after the first
#   successful creation. Re-running skips the POST and re-mints the JWT with
#   the cached ID, then refreshes the Kubernetes Secret. Safe to run whenever
#   the agent JWT needs to be rotated.
#
#   Why a local cache file: the platform's GET /api/organizations is scoped
#   to the caller's JWT org claim, so it can't be used for a name lookup.
#   POST /api/organizations doesn't deduplicate by name (no UNIQUE constraint).
#   Caching the ID locally is the simplest way to make re-runs a no-op for
#   the org while still refreshing the JWT.
#
#   To force a brand-new org: delete .platform/sandbox-org-id before running.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/common.sh
source "${SCRIPT_DIR}/common.sh"

SANDBOX_ORG_NAME="${SANDBOX_ORG_NAME:-sandbox-org}"
CLUSTER_CLAIM="${CLUSTER_CLAIM:-validation-sandbox}"
K8S_NAMESPACE="validation"
K8S_SECRET_NAME="platform-api-key"
JWT_SECRET_KEY="jwt-token"
ORG_ID_CACHE="${REPO_ROOT}/.platform/sandbox-org-id"

# ---------------------------------------------------------------------------
# Prerequisites
# ---------------------------------------------------------------------------

require_cmd terraform
require_cmd kubectl
require_cmd curl
require_cmd jq
require_cmd uuidgen
check_gcloud

# ---------------------------------------------------------------------------
# Resolve Cloud Run platform URL
# ---------------------------------------------------------------------------

if [[ -z "${PLATFORM_URL:-}" ]]; then
  info "Reading Cloud Run platform URL from Terraform output..."
  PLATFORM_URL="$(terraform -chdir="${REPO_ROOT}/infra/platform" output -raw platform_service_url)"
fi
info "Platform URL: ${PLATFORM_URL}"

# ---------------------------------------------------------------------------
# Point kubectl at the sandbox cluster
# ---------------------------------------------------------------------------
#
# We always re-fetch credentials so re-running this script doesn't depend on
# whatever context the operator left in their kubeconfig. Idempotent.

info "Fetching sandbox cluster credentials..."
SANDBOX_CLUSTER_NAME="$(terraform -chdir="${REPO_ROOT}/infra/sandbox" output -raw cluster_name)"
SANDBOX_CLUSTER_LOCATION="$(terraform -chdir="${REPO_ROOT}/infra/sandbox" output -raw cluster_location)"
gcloud container clusters get-credentials "${SANDBOX_CLUSTER_NAME}" \
  --region="${SANDBOX_CLUSTER_LOCATION}" \
  --project="${PROJECT}" >/dev/null

# ---------------------------------------------------------------------------
# Pull the JWT private key from Secret Manager
# ---------------------------------------------------------------------------

info "Fetching JWT private key from Secret Manager..."
JWT_PRIVATE_KEY="$(gcloud secrets versions access latest \
  --secret=validation-jwt-private-key \
  --project="${PROJECT}")"
export JWT_PRIVATE_KEY

# ---------------------------------------------------------------------------
# Helper: mint a signed JWT via the platform's CLI tool
# ---------------------------------------------------------------------------
#
# JwtTokenGenerator prints exactly the token to stdout. Gradle is muted with
# --quiet, and stderr (where Gradle keeps build progress / "BUILD SUCCESSFUL")
# is dropped. tail -1 is a defensive guard against any leakage.

mint_jwt() {
  local org_id="$1"
  local cluster="$2"
  "${REPO_ROOT}/gradlew" --quiet -p "${REPO_ROOT}" :platform:generateToken \
    --args="--org ${org_id} --cluster ${cluster}" \
    2>/dev/null | tail -1
}

# ---------------------------------------------------------------------------
# Org bootstrap (cached)
# ---------------------------------------------------------------------------

ORG_ID=""
if [[ -f "${ORG_ID_CACHE}" ]]; then
  ORG_ID="$(cat "${ORG_ID_CACHE}")"
  info "Using cached org ID: ${ORG_ID} (from ${ORG_ID_CACHE})"
fi

if [[ -z "${ORG_ID}" ]]; then
  info "No cached org ID — creating org '${SANDBOX_ORG_NAME}'..."

  # The platform doesn't validate the org claim against the DB on
  # POST /api/organizations (see TODO in platform/.../Routes.kt). Any valid
  # JWT works for this one call, so we mint a throwaway with a random UUID.
  TEMP_ADMIN_ORG_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
  TEMP_ADMIN_JWT="$(mint_jwt "${TEMP_ADMIN_ORG_ID}" "${CLUSTER_CLAIM}")"
  if [[ -z "${TEMP_ADMIN_JWT}" ]]; then
    die "Failed to mint temp admin JWT (gradle :platform:generateToken returned no output)"
  fi

  RESPONSE_BODY_FILE="$(mktemp)"
  trap 'rm -f "${RESPONSE_BODY_FILE}"' EXIT
  HTTP_STATUS="$(curl --silent --show-error \
    --output "${RESPONSE_BODY_FILE}" \
    --write-out '%{http_code}' \
    -X POST \
    -H "Authorization: Bearer ${TEMP_ADMIN_JWT}" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"${SANDBOX_ORG_NAME}\"}" \
    "${PLATFORM_URL}/api/organizations")"

  if [[ "${HTTP_STATUS}" != "201" ]]; then
    die "POST /api/organizations returned HTTP ${HTTP_STATUS}: $(cat "${RESPONSE_BODY_FILE}")"
  fi

  ORG_ID="$(jq -r '.id' < "${RESPONSE_BODY_FILE}")"
  if [[ -z "${ORG_ID}" || "${ORG_ID}" == "null" ]]; then
    die "Could not parse org ID from response: $(cat "${RESPONSE_BODY_FILE}")"
  fi

  mkdir -p "$(dirname "${ORG_ID_CACHE}")"
  echo "${ORG_ID}" > "${ORG_ID_CACHE}"
  success "Created org '${SANDBOX_ORG_NAME}' (id ${ORG_ID}); cached to ${ORG_ID_CACHE}"
fi

# ---------------------------------------------------------------------------
# Mint the real agent JWT
# ---------------------------------------------------------------------------

info "Minting agent JWT (org=${ORG_ID}, cluster=${CLUSTER_CLAIM})..."
AGENT_JWT="$(mint_jwt "${ORG_ID}" "${CLUSTER_CLAIM}")"
if [[ -z "${AGENT_JWT}" ]]; then
  die "Failed to mint agent JWT (gradle :platform:generateToken returned no output)"
fi

# ---------------------------------------------------------------------------
# Write the JWT to the sandbox cluster as a Kubernetes Secret
# ---------------------------------------------------------------------------

info "Ensuring namespace '${K8S_NAMESPACE}' exists..."
kubectl create namespace "${K8S_NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

info "Writing Secret '${K8S_SECRET_NAME}' to namespace '${K8S_NAMESPACE}'..."
kubectl create secret generic "${K8S_SECRET_NAME}" \
  --from-literal="${JWT_SECRET_KEY}=${AGENT_JWT}" \
  --namespace="${K8S_NAMESPACE}" \
  --dry-run=client -o yaml \
  | kubectl apply -f -

success "Agent JWT in Secret ${K8S_NAMESPACE}/${K8S_SECRET_NAME} (key: ${JWT_SECRET_KEY})"

cat <<EOF

Org seeding complete.

  Org ID:        ${ORG_ID}
  Org name:      ${SANDBOX_ORG_NAME}
  Cluster claim: ${CLUSTER_CLAIM}
  K8s Secret:    ${K8S_NAMESPACE}/${K8S_SECRET_NAME} (key: ${JWT_SECRET_KEY})

Verify:
  AGENT_JWT=\$(kubectl get secret ${K8S_SECRET_NAME} -n ${K8S_NAMESPACE} \\
    -o jsonpath='{.data.${JWT_SECRET_KEY}}' | base64 -d)
  curl -H "Authorization: Bearer \${AGENT_JWT}" ${PLATFORM_URL}/api/organizations

EOF
