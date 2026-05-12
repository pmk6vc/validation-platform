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
# Destroy (with one-shot stale-lock recovery)
# ---------------------------------------------------------------------------
# Aborted Terraform runs can leave a lock in GCS; the next destroy fails with
# "Error acquiring the state lock". The sandbox is single-user, so on that
# specific error we parse the lock ID, force-unlock, and retry once.

run_destroy() {
  # Run with `set +e` so the pipeline's non-zero exit (under pipefail) does not
  # short-circuit the caller before we can inspect ${TF_LOG_FILE}.
  set +e
  terraform -chdir="${REPO_ROOT}/infra/sandbox" destroy -auto-approve 2>&1 | tee "$1"
  local rc="${PIPESTATUS[0]}"
  set -e
  return "${rc}"
}

TF_LOG_FILE="$(mktemp -t sandbox-down)"
trap 'rm -f "${TF_LOG_FILE}"' EXIT

info "Destroying sandbox stack (GKE cluster + node pool)..."
if ! run_destroy "${TF_LOG_FILE}"; then
  if grep -q "Error acquiring the state lock" "${TF_LOG_FILE}"; then
    # Terraform colorizes output, so ANSI escape codes precede "ID:" on the
    # lock-info line. Match anywhere on the line and extract the trailing digits.
    LOCK_ID="$(grep -E 'ID:[[:space:]]+[0-9]+' "${TF_LOG_FILE}" | head -1 | grep -oE '[0-9]+$')"
    [[ -n "${LOCK_ID}" ]] || die "Could not parse stale lock ID from terraform output."
    warn "Stale Terraform state lock detected (ID: ${LOCK_ID}). Force-unlocking and retrying."
    terraform -chdir="${REPO_ROOT}/infra/sandbox" force-unlock -force "${LOCK_ID}"
    run_destroy "${TF_LOG_FILE}" || die "Destroy failed after force-unlock."
  else
    die "terraform destroy failed (not a lock issue — see output above)."
  fi
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

echo ""
success "Sandbox cluster destroyed. All compute charges stopped."
echo ""
echo "To recreate the cluster:"
echo "  ./scripts/sandbox-up.sh"
echo ""
