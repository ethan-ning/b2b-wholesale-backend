-- A tier becomes a standing agreement rather than a label.
--
-- Until now a dealer's price existed only if someone typed one for that SKU and that
-- tier, so an imported catalogue was unsellable until every SKU had been priced by hand.
-- A discount on the tier prices everything the moment it arrives, and a per-SKU row stays
-- available for the cases that need one.

ALTER TABLE customer_tier
    ADD COLUMN discount_percent NUMERIC(5, 2) NOT NULL DEFAULT 0
        CHECK (discount_percent >= 0 AND discount_percent < 100);

-- Existing tiers keep what they were charging in practice: across the catalogue Gold sat
-- near 82% of list and Silver near 93%, so these are the rates already in use rather than
-- new ones. Adjustable from the back office afterwards.
UPDATE customer_tier SET discount_percent = 18, sort_order = 3 WHERE name = 'Gold';
UPDATE customer_tier SET discount_percent =  7, sort_order = 2 WHERE name = 'Silver';

-- Where a dealer lands with nothing negotiated: list price, no discount.
INSERT INTO customer_tier (name, sort_order, discount_percent)
VALUES ('Default', 1, 0)
ON CONFLICT (name) DO NOTHING;

-- The per-SKU rows go. Every one of them was the old scheme's way of saying "this tier
-- pays this much", generated wholesale rather than negotiated, and keeping them would
-- mark the entire catalogue as overridden — leaving the discounts with nothing to price
-- and no way to see them working. What remains is a table that is empty until somebody
-- deliberately departs from a tier's rate.
DELETE FROM tier_price;
