-- Remove FK from captured_inputs.service_id to services.id.
-- The collector module should not depend on app's tables at the database level.
-- Referential integrity is enforced at the application layer: the agent registers
-- services with the app, receives IDs, and uses those IDs when posting to the collector.
ALTER TABLE captured_inputs DROP CONSTRAINT captured_inputs_service_id_fkey;
