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
# Apply with Cloud SQL paused
# ---------------------------------------------------------------------------

info "Pausing Cloud SQL (activation_policy=NEVER)..."
terraform -chdir="${REPO_ROOT}/infra/platform" apply \
  -auto-approve \
  -var="cloudsql_active=false" \
  -var="platform_image=${PLATFORM_IMAGE}" \
  -var="collector_image=${COLLECTOR_IMAGE}"

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
