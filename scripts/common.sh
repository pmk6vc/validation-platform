#!/usr/bin/env bash
# common.sh — shared helpers sourced by lifecycle scripts.
# Do not execute directly.

# shellcheck disable=SC2034
# (PROJECT, REGION, REGISTRY, PLACEHOLDER_IMAGE, REPO_ROOT are used by sourcing scripts)
PROJECT="${PROJECT:-zugzwang-381922}"
REGION="${REGION:-us-central1}"

REGISTRY="${REGION}-docker.pkg.dev/${PROJECT}/validation"
PLACEHOLDER_IMAGE="us-docker.pkg.dev/cloudrun/container/hello"

# Absolute path to the repo root (one level up from scripts/)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ---------------------------------------------------------------------------
# Logging helpers
# ---------------------------------------------------------------------------

info()    { echo "[INFO]  $*"; }
success() { echo "[OK]    $*"; }
warn()    { echo "[WARN]  $*" >&2; }
die()     { echo "[ERROR] $*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Prerequisite checks
# ---------------------------------------------------------------------------

require_cmd() {
  local cmd="$1"
  command -v "${cmd}" &>/dev/null || die "'${cmd}' is not installed or not on PATH."
}

# Verify gcloud is authenticated and the active project matches $PROJECT.
check_gcloud() {
  require_cmd gcloud
  local active
  active="$(gcloud config get-value project 2>/dev/null)"
  if [[ "${active}" != "${PROJECT}" ]]; then
    die "Active gcloud project is '${active}', expected '${PROJECT}'. Run: gcloud config set project ${PROJECT}"
  fi
  # Light auth check — list storage buckets; fails if unauthenticated.
  gcloud auth print-access-token &>/dev/null \
    || die "gcloud is not authenticated. Run: gcloud auth application-default login"
}

# ---------------------------------------------------------------------------
# Image resolution
# ---------------------------------------------------------------------------

# Print the most-recently-pushed digest/tag for a given Artifact Registry repo.
# Falls back to PLACEHOLDER_IMAGE if no images exist yet.
latest_image() {
  local repo="$1"   # e.g. "platform" or "collector"
  local full_repo="${REGISTRY}/${repo}"
  local tag
  tag="$(gcloud artifacts docker images list "${full_repo}" \
    --project="${PROJECT}" \
    --include-tags \
    --sort-by="~CREATE_TIME" \
    --limit=1 \
    --format="value(tags)" 2>/dev/null | head -1)"

  if [[ -z "${tag}" ]]; then
    echo "${PLACEHOLDER_IMAGE}"
  else
    echo "${full_repo}:${tag}"
  fi
}

# Read the current image deployed to a Cloud Run service from Terraform state.
# Usage: current_cloudrun_image <terraform-chdir> <output-name>
# We derive it by inspecting the Terraform state outputs for the service URL,
# then reading the active revision image via gcloud.
current_platform_image() {
  local svc="validation-platform"
  gcloud run services describe "${svc}" \
    --project="${PROJECT}" \
    --region="${REGION}" \
    --format="value(spec.template.spec.containers[0].image)" 2>/dev/null \
    || echo "${PLACEHOLDER_IMAGE}"
}

current_collector_image() {
  local svc="validation-collector"
  gcloud run services describe "${svc}" \
    --project="${PROJECT}" \
    --region="${REGION}" \
    --format="value(spec.template.spec.containers[0].image)" 2>/dev/null \
    || echo "${PLACEHOLDER_IMAGE}"
}
