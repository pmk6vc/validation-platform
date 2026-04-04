-- Convert all TIMESTAMP columns to TIMESTAMPTZ to avoid silent misinterpretation
-- when the database server timezone is not UTC.
-- This is a no-op for existing data if the server timezone is already UTC.

ALTER TABLE organizations ALTER COLUMN created_at TYPE TIMESTAMPTZ;
ALTER TABLE services ALTER COLUMN discovered_at TYPE TIMESTAMPTZ;
ALTER TABLE services ALTER COLUMN last_seen_at TYPE TIMESTAMPTZ;
ALTER TABLE captured_inputs ALTER COLUMN captured_at TYPE TIMESTAMPTZ;
