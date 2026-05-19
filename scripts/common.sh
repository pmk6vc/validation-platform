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
  # gcloud's value(tags) joins all tags on a single image with commas
  # (push_main.yml pushes each image with both :<sha> and :latest).
  # cut -d',' -f1 picks just the first tag — Cloud Run rejects multi-tag refs
  # like "platform:<sha>,latest" with a 400 "parsing failed" error.
  tag="$(gcloud artifacts docker images list "${full_repo}" \
    --project="${PROJECT}" \
    --include-tags \
    --sort-by="~CREATE_TIME" \
    --limit=1 \
    --format="value(tags)" 2>/dev/null | head -1 | cut -d',' -f1)"

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

# ---------------------------------------------------------------------------
# Cloud SQL ownership cleanup
# ---------------------------------------------------------------------------

# Strip Postgres-level ownership and grants from one or more IAM SQL users in
# the validation database so a subsequent DROP USER (via terraform destroy,
# or a terraform apply that removes a dev_db_users entry) succeeds.
#
# Postgres refuses to drop a role that still owns objects or holds grants on
# others' objects. The platform SA owns Flyway-created tables. Dev users own
# anything they created via the Cloud SQL Auth Proxy. Without cleanup,
# terraform fails with:
#   role "X" cannot be dropped because some objects depend on it
#
# Uses bootstrap-db.sh's temp-postgres-password pattern: set a random postgres
# password for ~seconds, drive psql via cloud-sql-proxy, then rotate it back
# to an unknown value on EXIT.
#
# Usage:
#   strip_postgres_ownership_for_roles "role1" "role2" ...
strip_postgres_ownership_for_roles() {
  local roles=("$@")
  [[ ${#roles[@]} -eq 0 ]] && return 0

  require_cmd cloud-sql-proxy
  require_cmd psql
  require_cmd openssl

  local instance_name="validation-postgres"
  local instance_conn="${PROJECT}:${REGION}:${instance_name}"
  local database="validation"
  local proxy_port="${PROXY_PORT:-5433}"
  local temp_pass
  temp_pass="$(openssl rand -base64 32)"

  info "Stripping Postgres ownership for ${#roles[@]} role(s) so DROP USER can succeed..."

  gcloud sql users set-password postgres \
    --instance="${instance_name}" \
    --project="${PROJECT}" \
    --password="${temp_pass}" >/dev/null

  cloud-sql-proxy --port="${proxy_port}" "${instance_conn}" >/dev/null 2>&1 &
  local proxy_pid=$!

  # Rotate the temporary postgres password and stop the proxy on any exit
  # path out of this helper.
  _strip_cleanup() {
    gcloud sql users set-password postgres \
      --instance="${instance_name}" \
      --project="${PROJECT}" \
      --password="$(openssl rand -base64 32)" >/dev/null 2>&1 || true
    if kill -0 "${proxy_pid}" 2>/dev/null; then
      kill "${proxy_pid}" 2>/dev/null || true
      wait "${proxy_pid}" 2>/dev/null || true
    fi
  }
  trap _strip_cleanup EXIT

  # Wait for the proxy to accept connections.
  local _i
  for _i in {1..15}; do
    if PGPASSWORD="${temp_pass}" psql -h 127.0.0.1 -p "${proxy_port}" -U postgres \
         -d postgres -c 'SELECT 1' >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done

  # Diagnostic helper: enumerate pg_shdepend dependency rows for a role in
  # the current database + shared (dbid=0). Resolves objid → human-readable
  # object name where the class is known. Helps see what's blocking a
  # DROP USER before/after the cleanup. Casts deptype/classid to text
  # explicitly to avoid the "operator is not unique: unknown || char" the
  # bare concatenation hits (deptype is the internal "char" type).
  _diagnose_role_deps() {
    local r="$1"
    PGPASSWORD="${temp_pass}" psql \
      -h 127.0.0.1 -p "${proxy_port}" \
      -U postgres -d "${database}" \
      -c "
        SELECT
          d.deptype::text AS deptype,
          d.classid::regclass::text AS class,
          d.dbid,
          d.objid,
          d.objsubid,
          CASE d.classid
            WHEN 'pg_class'::regclass THEN
              (SELECT n.nspname || '.' || c.relname
                 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.oid = d.objid)
            WHEN 'pg_namespace'::regclass THEN
              (SELECT nspname FROM pg_namespace WHERE oid = d.objid)
            WHEN 'pg_default_acl'::regclass THEN
              (SELECT pg_get_userbyid(defaclrole) || '/'
                   || COALESCE((SELECT nspname FROM pg_namespace WHERE oid = defaclnamespace), '*')
                   || '/' || defaclobjtype::text
                 FROM pg_default_acl WHERE oid = d.objid)
            WHEN 'pg_proc'::regclass THEN
              (SELECT n.nspname || '.' || p.proname
                 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE p.oid = d.objid)
            WHEN 'pg_type'::regclass THEN
              (SELECT n.nspname || '.' || t.typname
                 FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                WHERE t.oid = d.objid)
            WHEN 'pg_database'::regclass THEN
              (SELECT datname FROM pg_database WHERE oid = d.objid)
            ELSE '<unresolved>'
          END AS object_name
        FROM pg_shdepend d
        WHERE d.refobjid = (SELECT oid FROM pg_roles WHERE rolname = '${r}')
          AND d.dbid IN (0, (SELECT oid FROM pg_database WHERE datname = current_database()))
        ORDER BY d.deptype::text, d.classid::regclass::text, d.objid;
      " 2>&1 || true
  }

  local role
  for role in "${roles[@]}"; do
    info "  Cleaning ownership for: ${role}"

    info "    pg_shdepend BEFORE cleanup:"
    _diagnose_role_deps "${role}"

    # GRANT membership so REASSIGN works (REASSIGN requires postgres to be a
    # member of both source and target). Errors are surfaced — silently
    # swallowing them is what hid the partial-cleanup root cause in the
    # earlier attempt. Non-fatal because postgres may already be a member
    # (idempotent re-run).
    info "    GRANT ${role} TO postgres:"
    PGPASSWORD="${temp_pass}" psql \
      -h 127.0.0.1 -p "${proxy_port}" \
      -U postgres -d "${database}" \
      -c "GRANT \"${role}\" TO postgres WITH ADMIN OPTION;" 2>&1 || \
      warn "      GRANT failed (REASSIGN may not work)"

    info "    REASSIGN OWNED + DROP OWNED CASCADE:"
    # client_min_messages=ERROR suppresses the ~60 WARNING lines DROP OWNED
    # emits for tables/columns where the role has no actual privileges (PG
    # walks every pg_shdepend ACL entry and warns when the table ACL is
    # empty). Combined into a single -c so the SET persists for the two
    # following statements.
    PGPASSWORD="${temp_pass}" psql \
      -h 127.0.0.1 -p "${proxy_port}" \
      -U postgres -d "${database}" \
      -v ON_ERROR_STOP=1 \
      -c "SET client_min_messages TO ERROR;
          REASSIGN OWNED BY \"${role}\" TO postgres;
          DROP OWNED BY \"${role}\" CASCADE;" \
      2>&1 \
      || warn "      REASSIGN/DROP OWNED failed — DROP USER may still hit a dependency error."

    # DROP OWNED does NOT remove default privileges where the role is the
    # GRANTEE (only those where the role is the grantor). Walk pg_default_acl
    # and ALTER DEFAULT PRIVILEGES ... REVOKE for every entry naming our role
    # as a grantee. This is the gap that left 4 of 5 objects behind on the
    # last run.
    info "    Revoking default privileges granted TO ${role}:"
    # Role name interpolated from bash (psql :'var' doesn't substitute inside
    # dollar-quoted blocks). Safe because Cloud SQL user names can't contain
    # single quotes.
    PGPASSWORD="${temp_pass}" psql \
      -h 127.0.0.1 -p "${proxy_port}" \
      -U postgres -d "${database}" \
      -c "
DO \$strip\$
DECLARE
  rec RECORD;
  cmd TEXT;
  target TEXT := '${role}';
BEGIN
  FOR rec IN
    SELECT pg_get_userbyid(d.defaclrole) AS grantor,
           CASE WHEN d.defaclnamespace = 0 THEN NULL ELSE n.nspname END AS schema_name,
           d.defaclobjtype AS obj_type
    FROM pg_default_acl d
    LEFT JOIN pg_namespace n ON n.oid = d.defaclnamespace
    WHERE EXISTS (
      SELECT 1 FROM aclexplode(d.defaclacl) ax
      WHERE pg_get_userbyid(ax.grantee) = target
    )
  LOOP
    cmd := 'ALTER DEFAULT PRIVILEGES FOR ROLE ' || quote_ident(rec.grantor);
    IF rec.schema_name IS NOT NULL THEN
      cmd := cmd || ' IN SCHEMA ' || quote_ident(rec.schema_name);
    END IF;
    cmd := cmd || ' REVOKE ALL ON ' || CASE rec.obj_type
      WHEN 'r' THEN 'TABLES'
      WHEN 'S' THEN 'SEQUENCES'
      WHEN 'f' THEN 'FUNCTIONS'
      WHEN 'T' THEN 'TYPES'
      WHEN 'n' THEN 'SCHEMAS'
      ELSE NULL
    END || ' FROM ' || quote_ident(target);
    RAISE NOTICE 'Executing: %', cmd;
    EXECUTE cmd;
  END LOOP;
END
\$strip\$;
      " 2>&1 || warn "      ALTER DEFAULT PRIVILEGES revoke failed."

    # DROP OWNED above runs as postgres, which can REASSIGN ownership and
    # revoke default privileges, but cannot REVOKE grants on objects owned
    # by other roles (postgres is not the platform SA, and PG requires the
    # object owner — or a member with SET option — to REVOKE). The result
    # is "no privileges could be revoked for X" warnings and stale
    # pg_shdepend ACL rows that block DROP USER. Clear them now: walk the
    # residual pg_shdepend rows, GRANT the table-owner role TO postgres
    # WITH SET TRUE, SET LOCAL ROLE to that owner, and REVOKE.
    info "    Revoking residual ACL grants (as table owner):"
    PGPASSWORD="${temp_pass}" psql \
      -h 127.0.0.1 -p "${proxy_port}" \
      -U postgres -d "${database}" \
      -c "
DO \$residual\$
DECLARE
  rec RECORD;
  target TEXT := '${role}';
  cur_owner TEXT := NULL;
BEGIN
  FOR rec IN
    SELECT n.nspname AS schema_name,
           c.relname AS table_name,
           pg_get_userbyid(c.relowner) AS owner_name,
           c.relkind
    FROM pg_shdepend d
    JOIN pg_class c ON c.oid = d.objid AND d.classid = 'pg_class'::regclass
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE d.refobjid = (SELECT oid FROM pg_roles WHERE rolname = target)
      AND d.deptype = 'a'
      AND d.dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
    ORDER BY pg_get_userbyid(c.relowner)
  LOOP
    IF cur_owner IS DISTINCT FROM rec.owner_name THEN
      IF cur_owner IS NOT NULL THEN
        EXECUTE 'RESET ROLE';
      END IF;
      -- postgres has ADMIN OPTION on roles cloudsqlsuperuser created
      -- (which includes platform SA + dev users), so this GRANT is allowed.
      EXECUTE 'GRANT ' || quote_ident(rec.owner_name) || ' TO postgres WITH SET TRUE';
      EXECUTE 'SET LOCAL ROLE ' || quote_ident(rec.owner_name);
      cur_owner := rec.owner_name;
    END IF;
    EXECUTE format('REVOKE ALL ON %s %I.%I FROM %I',
      CASE rec.relkind
        WHEN 'S' THEN 'SEQUENCE'
        ELSE 'TABLE'
      END,
      rec.schema_name, rec.table_name, target);
    RAISE NOTICE 'Revoked ALL on %.% (kind=%) from %',
      rec.schema_name, rec.table_name, rec.relkind, target;
  END LOOP;
  IF cur_owner IS NOT NULL THEN
    EXECUTE 'RESET ROLE';
  END IF;
END
\$residual\$;
      " 2>&1 || warn "      Residual ACL revoke failed."

    info "    REVOKE membership:"
    PGPASSWORD="${temp_pass}" psql \
      -h 127.0.0.1 -p "${proxy_port}" \
      -U postgres -d "${database}" \
      -c "REVOKE \"${role}\" FROM postgres;" 2>&1 || true

    info "    pg_shdepend AFTER cleanup:"
    _diagnose_role_deps "${role}"
  done

  _strip_cleanup
  trap - EXIT
  success "Postgres-level cleanup complete. Postgres password is unknown again."
}

# Print the names (one per line) of google_sql_user resources that the given
# terraform plan file will drop. Empty output if none.
#
# Usage:
#   mapfile -t USERS < <(iam_users_terraform_will_drop <chdir> <plan_file>)
iam_users_terraform_will_drop() {
  local chdir="$1"
  local plan_file="$2"
  require_cmd python3
  terraform -chdir="${chdir}" show -json "${plan_file}" 2>/dev/null | \
    python3 -c '
import json, sys
data = json.load(sys.stdin)
for change in data.get("resource_changes", []):
    if change.get("type") != "google_sql_user":
        continue
    actions = change.get("change", {}).get("actions") or []
    if "delete" not in actions:
        continue
    before = change.get("change", {}).get("before") or {}
    name = before.get("name")
    if name:
        print(name)
'
}
