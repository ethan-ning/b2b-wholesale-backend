-- One sync, not two.
--
-- Catalog and stock were separate jobs with separate schedules and separate run records.
-- They are one operation: an admin picks the categories and the warehouses, and a run
-- imports the products and then counts them. Splitting it meant a freshly imported SKU
-- showed no stock until a different job happened to come round.
--
-- The job column goes with the split. A single-valued discriminator is a column that
-- only ever answers one question.

DROP INDEX IF EXISTS idx_sellfox_run_job;

ALTER TABLE sellfox_sync_run DROP CONSTRAINT IF EXISTS sellfox_sync_run_job_check;
ALTER TABLE sellfox_sync_run DROP COLUMN IF EXISTS job;
