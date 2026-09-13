-- base_wholesale_price stops being something anyone types.
--
-- It is now derived: the cheapest default price among a product's SKUs still on sale. No
-- pricing reads it. Search does — filtering and sorting a result list needs one figure per
-- product, and the cheapest is the one already shown on the dealer's card as "from $X", so
-- a price filter and the number beside it agree.

UPDATE product p
SET base_wholesale_price = COALESCE((
    SELECT MIN(tp.price)
    FROM product_variant v
    JOIN tier_price tp ON tp.sku = v.sku AND tp.min_qty = 1
    JOIN customer_tier t ON t.id = tp.tier_id AND t.is_anchor
    WHERE v.product_id = p.id AND v.status = 'ACTIVE'
), 0);
