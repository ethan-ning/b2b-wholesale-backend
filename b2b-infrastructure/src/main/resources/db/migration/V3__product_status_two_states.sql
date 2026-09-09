-- Products come from the ERP, so they are never drafts, and "archived" said nothing
-- that "inactive" did not. Two states, one rule: is this visible to dealers.
--
-- Both former states meant "not visible", so both fold into INACTIVE without loss.

UPDATE product SET status = 'INACTIVE' WHERE status IN ('DRAFT', 'ARCHIVED');

ALTER TABLE product DROP CONSTRAINT IF EXISTS product_status_check;
ALTER TABLE product ADD CONSTRAINT product_status_check
    CHECK (status IN ('ACTIVE', 'INACTIVE'));

-- A synced product arrives real and visible; hiding it is a deliberate act.
ALTER TABLE product ALTER COLUMN status SET DEFAULT 'ACTIVE';
