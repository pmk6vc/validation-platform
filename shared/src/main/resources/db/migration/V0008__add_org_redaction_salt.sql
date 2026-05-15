-- V0008 — Per-org redaction salt for deterministic typed placeholders.
--
-- Phase 1 Plan 01-03 / CONTEXT.md D-15: the Go agent's redaction engine
-- produces typed placeholders of the form `<REDACTED:<type>:<hex6>>`
-- where the hex digest is `sha256(salt || ':' || value)[0..6]`. The salt
-- is per-organization so the same secret value hashes differently across
-- tenants — replay engine can match identical auth contexts within an
-- org without leaking comparability across orgs.
--
-- Generation: 32 random bytes hex-encoded = 64-char string. pgcrypto's
-- gen_random_bytes() is already available in Postgres 16 (extension is
-- enabled by default on Cloud SQL Postgres 16). The CREATE EXTENSION
-- statement is idempotent.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE organizations ADD COLUMN redaction_salt TEXT;

UPDATE organizations
SET redaction_salt = encode(gen_random_bytes(32), 'hex')
WHERE redaction_salt IS NULL;

ALTER TABLE organizations ALTER COLUMN redaction_salt SET NOT NULL;
