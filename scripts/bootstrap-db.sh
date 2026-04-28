#!/usr/bin/env bash
# bootstrap-db.sh — One-shot Cloud SQL bootstrap. Run once per DB lifetime
# (i.e., after a brand-new platform-up, or after the validation database has
# been dropped and recreated).
#
# What it does:
#   1. Sets a random temporary password on the built-in `postgres` superuser.
#   2. Connects via cloud-sql-proxy and runs the privileged grants:
#        - GRANT cloudsqlsuperuser TO each var.db_admin_users entry
#        - ALTER SCHEMA public OWNER TO the platform service account
#   3. Rotates the postgres password to another random value nobody knows.
#
# After this finishes, no human or service knows a postgres password. All
# future admin access uses IAM auth via cloud-sql-proxy --auto-iam-authn,
# authenticated as a CLOUD_IAM_USER granted cloudsqlsuperuser by this script.
#
# Idempotent: GRANT and ALTER SCHEMA … OWNER are no-ops if already applied.
# Safe to re-run if you add a new admin to terraform.tfvars and want them
# granted superuser without waiting for the next null_resource pass.
#
# Required env / context:
#   - gcloud authenticated as a project owner (needs sql.users.update)
#   - cloud-sql-proxy and psql installed locally
#   - terraform.tfvars present in infra/platform/ with db_admin_users set
#     (or pass admin emails as positional args, e.g. ./bootstrap-db.sh you@x.com)
#
# Usage:
#   ./scripts/bootstrap-db.sh                    # reads admins from tfvars
#   ./scripts/bootstrap-db.sh you@x.com a@b.com  # explicit override

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/common.sh
source "${SCRIPT_DIR}/common.sh"

INSTANCE_NAME="validation-postgres"
INSTANCE_CONNECTION="${PROJECT}:${REGION}:${INSTANCE_NAME}"
DATABASE="validation"
APP_USER="validation-platform-sa@${PROJECT}.iam"
PROXY_PORT="${PROXY_PORT:-5433}"

require_cmd gcloud
require_cmd cloud-sql-proxy
require_cmd psql
require_cmd openssl
check_gcloud

# ---------------------------------------------------------------------------
# Resolve admin user list
# ---------------------------------------------------------------------------

if [[ $# -gt 0 ]]; then
  ADMIN_USERS=("$@")
else
  TFVARS="${REPO_ROOT}/infra/platform/terraform.tfvars"
  [[ -f "${TFVARS}" ]] || die "${TFVARS} not found and no admin emails passed as args"
  # Extract emails from db_admin_users = ["a@b.com", "c@d.com"]
  mapfile -t ADMIN_USERS < <(
    grep -E '^\s*db_admin_users\s*=' "${TFVARS}" \
      | grep -oE '"[^"]+"' \
      | tr -d '"'
  )
  [[ ${#ADMIN_USERS[@]} -gt 0 ]] || die "No db_admin_users found in ${TFVARS}"
fi

info "Admin users to grant cloudsqlsuperuser: ${ADMIN_USERS[*]}"
info "App SA (will own public schema): ${APP_USER}"

# ---------------------------------------------------------------------------
# Step 1: temporary postgres password
# ---------------------------------------------------------------------------

TEMP_PASS="$(openssl rand -base64 32)"
info "Setting temporary postgres password..."
gcloud sql users set-password postgres \
  --instance="${INSTANCE_NAME}" \
  --project="${PROJECT}" \
  --password="${TEMP_PASS}" >/dev/null

# Always rotate to unknown on exit, even on failure.
rotate_to_unknown() {
  info "Rotating postgres password to unknown value..."
  gcloud sql users set-password postgres \
    --instance="${INSTANCE_NAME}" \
    --project="${PROJECT}" \
    --password="$(openssl rand -base64 32)" >/dev/null
  unset TEMP_PASS
}

# ---------------------------------------------------------------------------
# Step 2: start proxy, run grants
# ---------------------------------------------------------------------------

info "Starting cloud-sql-proxy on port ${PROXY_PORT}..."
cloud-sql-proxy --port="${PROXY_PORT}" "${INSTANCE_CONNECTION}" >/dev/null 2>&1 &
PROXY_PID=$!

cleanup() {
  rotate_to_unknown
  if kill -0 "${PROXY_PID}" 2>/dev/null; then
    kill "${PROXY_PID}" 2>/dev/null || true
    wait "${PROXY_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# Wait for the proxy to accept connections.
for _ in {1..15}; do
  if PGPASSWORD="${TEMP_PASS}" psql -h 127.0.0.1 -p "${PROXY_PORT}" -U postgres \
       -d postgres -c 'SELECT 1' >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

info "Running privileged grants..."
GRANT_SQL=""
for user in "${ADMIN_USERS[@]}"; do
  # GRANT in postgres is idempotent; safe to re-run.
  GRANT_SQL+="GRANT cloudsqlsuperuser TO \"${user}\";"$'\n'
done
GRANT_SQL+="ALTER SCHEMA public OWNER TO \"${APP_USER}\";"$'\n'
GRANT_SQL+="GRANT ALL ON SCHEMA public TO \"${APP_USER}\";"$'\n'

PGPASSWORD="${TEMP_PASS}" psql \
  -h 127.0.0.1 -p "${PROXY_PORT}" \
  -U postgres -d "${DATABASE}" \
  -v ON_ERROR_STOP=1 \
  <<< "${GRANT_SQL}"

# cleanup() (trap) handles password rotation + proxy shutdown.

success "Bootstrap complete."
echo ""
echo "From now on, admin DB access uses IAM auth as one of:"
for user in "${ADMIN_USERS[@]}"; do
  echo "  - ${user}"
done
echo ""
echo "To connect:"
echo "  cloud-sql-proxy --auto-iam-authn --port=5433 ${INSTANCE_CONNECTION}"
echo "  psql \"host=127.0.0.1 port=5433 user=<your-email> dbname=${DATABASE}\""
