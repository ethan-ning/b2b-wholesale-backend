-- A third mode: recompute the SPU grouping without going to Sellfox.
--
-- Grouping is a local calculation over data already imported — the declared SPU, the
-- declared pack children, and the SKU codes themselves. Only a full sync could correct it
-- before, which meant two minutes of paging the whole catalog to fix an answer that
-- needed no new facts. This mode reruns just the calculation.
ALTER TABLE sellfox_sync_run DROP CONSTRAINT IF EXISTS sellfox_sync_run_mode_check;
ALTER TABLE sellfox_sync_run ADD CONSTRAINT sellfox_sync_run_mode_check
    CHECK (mode IN ('FULL', 'INVENTORY', 'REGROUP'));

-- A regroup needs to rebuild the commodity facts from what was imported, and the child
-- quantity is the one it could not: pack_quantity on the variant is the *result* of
-- grouping, so reading it back would make the calculation depend on its own last answer.
ALTER TABLE sellfox_sku_link ADD COLUMN declared_spu TEXT;
ALTER TABLE sellfox_sku_link ADD COLUMN base_quantity INT;
ALTER TABLE sellfox_sku_link ADD COLUMN commodity_name TEXT;
