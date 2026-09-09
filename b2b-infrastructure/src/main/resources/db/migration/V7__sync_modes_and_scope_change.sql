-- Two cadences over one scope.
--
-- The scope is a fixed decision for this site — which product lines it carries and which
-- warehouses can ship them — so it is set once and then left alone. What differs is how
-- deep a run goes:
--
--   FULL       hourly is too expensive: it pages every commodity Sellfox holds, imports
--              the ones in scope, and deactivates the ones that have fallen out of it.
--   INVENTORY  stock only, for the selected warehouses. Cheap enough to run hourly, and
--              stock is the part that actually moves between catalog changes.
--
-- This is not the two-job split that was removed in V5. There is still one scope and one
-- place to set it; a mode says how much of it a given run covers.
ALTER TABLE sellfox_sync_run ADD COLUMN mode TEXT NOT NULL DEFAULT 'FULL'
    CHECK (mode IN ('FULL', 'INVENTORY'));

-- Changing the scope is its own reason for a run, and worth telling apart from the
-- nightly one: it is the only run that can deactivate a product an admin was selling.
ALTER TABLE sellfox_sync_run DROP CONSTRAINT IF EXISTS sellfox_sync_run_trigger_source_check;
ALTER TABLE sellfox_sync_run ADD CONSTRAINT sellfox_sync_run_trigger_source_check
    CHECK (trigger_source IN ('SCHEDULED', 'MANUAL', 'SCOPE_CHANGE'));

CREATE INDEX idx_sellfox_run_mode ON sellfox_sync_run (mode, started_at DESC);
