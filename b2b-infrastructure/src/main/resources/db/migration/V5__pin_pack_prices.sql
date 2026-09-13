-- Pack SKUs keep the price they had when the pack multiply was removed.
--
-- A SKU's price used to be the product's base price times what the pack holds, so a
-- 30-pack cost thirty times a single. That multiply is gone: the base price is what a SKU
-- lists at, whatever is in the box. Sound for the products whose base price is the price
-- of the thing sold — and wrong for the ones where it had been entered per unit, where a
-- 30-pack would suddenly sell for the price of one piece.
--
-- So every pack SKU is given the figure it charges today, as a per-SKU price. Dealers see
-- no change; the multiply stops being how prices are worked out; and each pack can be
-- repriced deliberately from the product page afterwards.
--
-- Singles are left alone. Their price was base x 1, which is what the tier's rate gives
-- anyway, and writing rows for them would mark the whole catalogue as hand-priced —
-- the thing V4 cleared out.

INSERT INTO tier_price (sku, tier_id, price, min_qty)
SELECT v.sku,
       t.id,
       -- The old rule, stated once more and then never again: base x pack, less the
       -- tier's rate. ROUND on numeric is half-up, matching Money.lessDiscount.
       ROUND(p.base_wholesale_price * v.pack_quantity * (1 - t.discount_percent / 100), 2),
       1
FROM product_variant v
JOIN product p ON p.id = v.product_id
CROSS JOIN customer_tier t
WHERE v.pack_quantity > 1
  -- A discount off nothing is nothing. Pinning a zero would hold the SKU at free even
  -- after somebody sets a real base price.
  AND p.base_wholesale_price > 0
-- Anything already priced by hand was a deliberate decision and outranks this.
ON CONFLICT (sku, tier_id, min_qty) DO NOTHING;
