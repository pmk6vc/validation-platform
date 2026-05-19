#!/usr/bin/env bash
# platform-down.sh — Pause the platform stack for cost savings.
#
# Sets Cloud SQL activation_policy to NEVER (~$2.50/mo storage-only vs ~$13/mo running).
# Cloud Run already scales to zero on its own — no action needed there.
#
# The currently-deployed images are read from Cloud Run so we don't accidentally
# redeploy different images when only changing the cloudsql_active flag.
#
# Usage:
#   ./scripts/platform-down.sh
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
# Read the images currently deployed to Cloud Run.
# This ensures the apply only changes cloudsql_active and doesn't trigger
# an unintended image rollout.
# ---------------------------------------------------------------------------

info "Reading currently-deployed images from Cloud Run..."
PLATFORM_IMAGE="$(current_platform_image)"
COLLECTOR_IMAGE="$(current_collector_image)"
info "Platform image:  ${PLATFORM_IMAGE}"
info "Collector image: ${COLLECTOR_IMAGE}"

# ---------------------------------------------------------------------------
# Plan → strip IAM-user ownership for users about to be dropped → apply
#
# If the user has removed an entry from var.dev_db_users (or any other
# google_sql_user is being destroyed), Postgres will refuse to DROP USER
# while that role still owns objects or holds grants. We plan first, scan
# the plan for google_sql_user deletes, strip Postgres-level ownership for
# only those roles, then apply the saved plan. See common.sh for helpers.
# ---------------------------------------------------------------------------

info "Planning the down apply (Cloud SQL active=false)..."
PLAN_FILE="$(mktemp -t platform-down-plan.XXXXXX)"
terraform -chdir="${REPO_ROOT}/infra/platform" plan \
  -out="${PLAN_FILE}" \
  -var="cloudsql_active=false" \
  -var="platform_image=${PLATFORM_IMAGE}" \
  -var="collector_image=${COLLECTOR_IMAGE}"

# Portable bash 3.2 array-from-stream (mapfile is bash 4+; macOS ships 3.2).
USERS_TO_DROP=()
while IFS= read -r _user; do
  [[ -n "${_user}" ]] && USERS_TO_DROP+=("${_user}")
done < <(
  iam_users_terraform_will_drop "${REPO_ROOT}/infra/platform" "${PLAN_FILE}"
)

if [[ ${#USERS_TO_DROP[@]} -gt 0 ]]; then
  info "Plan will drop ${#USERS_TO_DROP[@]} SQL user(s): ${USERS_TO_DROP[*]}"
  strip_postgres_ownership_for_roles "${USERS_TO_DROP[@]}"
fi

info "Applying the saved plan..."
terraform -chdir="${REPO_ROOT}/infra/platform" apply -auto-approve "${PLAN_FILE}"
rm -f "${PLAN_FILE}"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

echo ""
success "Platform stack is paused."
echo ""
echo "Cost breakdown while paused:"
echo "  Cloud SQL storage  : ~\$2.50/mo  (was ~\$7/mo running)"
echo "  Cloud Run          : \$0/mo      (scales to zero automatically)"
echo "  Total              : ~\$2.50/mo  (down from ~\$13/mo)"
echo ""
echo "To resume (bring Cloud SQL back up):"
echo "  ./scripts/platform-up.sh"
echo ""
