-- The Default tier becomes the price everything else is worked out from.
--
-- Until now every tier discounted off product.base_wholesale_price, so the one figure was
-- both what the business pays a supplier and what the cheapest dealer tier pays. Those are
-- different numbers with different reasons to change, and tying them together meant a cost
-- correction silently moved every dealer's price.
--
-- Now: a SKU's Default price is stated, and Silver and Gold take their discount off it.
-- base_wholesale_price keeps only its first meaning — supplier cost — and reaches nothing.

ALTER TABLE customer_tier
    ADD COLUMN is_anchor BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE customer_tier SET is_anchor = TRUE WHERE name = 'Default';

-- Exactly one, enforced rather than assumed: two anchors would make "the price others are
-- worked out from" ambiguous, and none would leave every SKU unpriced.
CREATE UNIQUE INDEX one_anchor_tier ON customer_tier ((is_anchor)) WHERE is_anchor;

-- Every SKU needs a Default price, because nothing else has one without it. It is what the
-- SKU charges the Default tier today: the row V5 pinned for a pack, or the product's base
-- price for everything else, which is what a 0%-discount tier was already paying.
INSERT INTO tier_price (sku, tier_id, price, min_qty)
SELECT v.sku, t.id, p.base_wholesale_price, 1
FROM product_variant v
JOIN product p ON p.id = v.product_id
JOIN customer_tier t ON t.is_anchor
WHERE p.base_wholesale_price > 0
ON CONFLICT (sku, tier_id, min_qty) DO NOTHING;

-- The Silver and Gold rows V5 pinned can go: they were the discount applied to the same
-- figure that is now the Default price, so deleting them leaves the arithmetic to state
-- what they already say. Only rows that agree with the rule are removed — anything
-- someone set to a different number was a decision and stays.
DELETE FROM tier_price tp
USING customer_tier t, tier_price anchor_row, customer_tier anchor_tier
WHERE tp.tier_id = t.id
  AND NOT t.is_anchor
  AND anchor_tier.is_anchor
  AND anchor_row.sku = tp.sku
  AND anchor_row.tier_id = anchor_tier.id
  AND anchor_row.min_qty = 1
  AND tp.min_qty = 1
  AND tp.price = ROUND(anchor_row.price * (1 - t.discount_percent / 100), 2);
