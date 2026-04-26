-- Add organization_id to captured_inputs so the collector can scope all
-- read/write operations to the caller's tenant without making a cross-module
-- HTTP call back to the platform.
--
-- Existing rows (if any) receive a sentinel value so NOT NULL is enforceable
-- going forward. In practice this migration runs on a fresh schema, but the
-- default keeps it safe on any instance that already has rows.
ALTER TABLE captured_inputs
    ADD COLUMN organization_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000';

CREATE INDEX idx_captured_inputs_organization_id ON captured_inputs(organization_id);
