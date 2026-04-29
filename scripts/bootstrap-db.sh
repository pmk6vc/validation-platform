#!/usr/bin/env bash
# bootstrap-db.sh — Grant the platform service account ownership of the
# `public` schema in the validation database, so Flyway can create tables
# when the Cloud Run app starts.
#
# Run once per DB lifetime: after the first platform-up.sh, or after the
# validation database has been dropped and recreated.
#
# How it works:
#   1. Sets a random temporary password on the built-in `postgres` superuser
#      (the only role with privilege to ALTER SCHEMA OWNER on `public`).
#   2. Connects via cloud-sql-proxy and runs the privileged grant.
#   3. Rotates the postgres password to another random value nobody knows.
#
# Total exposure window: ~5 seconds. Before and after, no human or service
# knows the postgres password.
#
# Idempotent — `ALTER SCHEMA … OWNER` is a no-op when the owner is already
# correct, so re-running this script is harmless.
#
# Required: gcloud authenticated as a project owner (needs sql.users.update),
# plus cloud-sql-proxy and psql installed locally.

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

info "App SA (will own public schema): ${APP_USER}"

TEMP_PASS="$(openssl rand -base64 32)"
info "Setting temporary postgres password..."
gcloud sql users set-password postgres \
  --instance="${INSTANCE_NAME}" \
  --project="${PROJECT}" \
  --password="${TEMP_PASS}" >/dev/null

info "Starting cloud-sql-proxy on port ${PROXY_PORT}..."
cloud-sql-proxy --port="${PROXY_PORT}" "${INSTANCE_CONNECTION}" >/dev/null 2>&1 &
PROXY_PID=$!

# Always rotate the password to unknown and stop the proxy on exit, even
# if the grant below fails. After this trap fires no human or service
# knows the postgres password.
cleanup() {
  info "Rotating postgres password to unknown value..."
  gcloud sql users set-password postgres \
    --instance="${INSTANCE_NAME}" \
    --project="${PROJECT}" \
    --password="$(openssl rand -base64 32)" >/dev/null || true
  unset TEMP_PASS
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

info "Granting CREATE/USAGE on public schema to the platform SA..."
# We don't ALTER OWNER because Cloud SQL's postgres user is cloudsqlsuperuser,
# not a real PG superuser, and ALTER SCHEMA … OWNER requires the granter to
# be a member of the target role (which postgres isn't). GRANT ALL is
# sufficient for Flyway: the SA can CREATE tables in public, and tables it
# creates are owned by the SA — no ownership transfer needed.
PGPASSWORD="${TEMP_PASS}" psql \
  -h 127.0.0.1 -p "${PROXY_PORT}" \
  -U postgres -d "${DATABASE}" \
  -v ON_ERROR_STOP=1 \
  -c "GRANT ALL ON SCHEMA public TO \"${APP_USER}\";"

success "Bootstrap complete. Postgres password is unknown again."
echo "The platform service account can now create tables in the public schema."
echo "Flyway migrations will succeed on the next Cloud Run deploy."
