-- Sample catalog for local development ONLY.
--
-- Applied because application-local.yml adds classpath:db/seed to spring.flyway.locations.
-- Production never lists that location, so this never runs there. The high version
-- number keeps it clear of real migrations.
--
-- Mirrors the portal's mock data, so the frontend behaves the same against either.

INSERT INTO category (id, name, slug, parent_id, sort_order) VALUES
    (1, 'Auto Parts', 'auto-parts', NULL, 1),
    (2, 'Apparel',    'apparel',    NULL, 2),
    (11, 'Exhaust',   'exhaust',    1, 1),
    (12, 'Lighting',  'lighting',   1, 2),
    (21, 'Jackets',   'jackets',    2, 1),
    (22, 'Gloves',    'gloves',     2, 2);
SELECT setval('category_id_seq', (SELECT MAX(id) FROM category));

-- Two products, one per variant axis: gloves vary by size, mufflers by pack quantity.
INSERT INTO product (id, spu_code, name, brand, description, base_wholesale_price, location_code, variant_axis, attributes_json, status) VALUES
    (1, 'GL100-BLK', 'Riding Gloves - Black', 'RiderEdge',
     'Touchscreen-compatible leather riding gloves, reinforced palm.',
     18.00, 'C2-1', 'Size', '{"Color":"Black","Material":"Genuine Leather"}', 'ACTIVE'),
    (2, 'PL001-BLK', 'Muffler Extension Pipe - Black', 'ProLine',
     'Heavy-duty stainless steel muffler extension pipe.',
     19.00, 'A1-1', 'Pack Qty', '{"Color":"Black","Material":"Stainless Steel"}', 'ACTIVE'),
    (3, 'LT200-WHT', 'LED Work Light Bar - White', 'LumenPro',
     'Deactivated product, not visible to dealers.',
     34.00, 'B3-1', 'Pack Qty', '{"Wattage":"120W"}', 'INACTIVE');
SELECT setval('product_id_seq', (SELECT MAX(id) FROM product));

-- sort_order is explicit: S < M < L < XL is not lexical.
INSERT INTO product_variant (id, product_id, sku, variant_value, sort_order, pack_quantity, map_price, upc, weight, status, available_stock, incoming_stock, stock_synced_at) VALUES
    (1, 1, 'GL100-BLK-S',  'S',  0, 1, 36.99, '056789000001', 0.30, 'ACTIVE', 22, 0,  NOW()),
    (2, 1, 'GL100-BLK-M',  'M',  1, 1, 36.99, '056789000002', 0.32, 'ACTIVE', 14, 0,  NOW()),
    (3, 1, 'GL100-BLK-L',  'L',  2, 1, 36.99, '056789000003', 0.34, 'ACTIVE',  4, 10, NOW()),
    (4, 1, 'GL100-BLK-XL', 'XL', 3, 1, 39.99, '056789000004', 0.36, 'ACTIVE',  0, 15, NOW()),
    (5, 2, 'PL001-BLK-01', '1',  0, 1,  39.99, '012345678901', 0.50, 'ACTIVE', 25, 0,  NOW()),
    (6, 2, 'PL001-BLK-06', '6',  1, 6, 239.94, '012345678902', 3.00, 'ACTIVE',  3, 12, NOW()),
    (7, 3, 'LT200-WHT-01', '1',  0, 1,  69.99, '034567890001', 1.20, 'ACTIVE', 15, 0,  NOW());
SELECT setval('product_variant_id_seq', (SELECT MAX(id) FROM product_variant));

INSERT INTO product_image (product_id, url, alt_text, sort_order) VALUES
    (1, 'https://placehold.co/400x300/222222/ffffff?text=Gloves+Black', 'Riding Gloves - Black', 0),
    (2, 'https://placehold.co/400x300/1a1a1a/ffffff?text=Muffler+Black', 'Muffler Extension Pipe - Black', 0);

INSERT INTO product_category (product_id, category_id, is_primary) VALUES
    (1, 22, TRUE),
    (2, 11, TRUE),
    (3, 12, TRUE);

-- One row per SKU per tier; price is what one of that SKU costs, so a 6-pack's row is
-- the whole pack. min_qty stays 1 — quantity-based pricing is deferred.
INSERT INTO tier_price (sku, tier_id, price, min_qty) VALUES
    ('GL100-BLK-S',  1,  14.00, 1), ('GL100-BLK-S',  2,  16.50, 1),
    ('GL100-BLK-M',  1,  14.00, 1), ('GL100-BLK-M',  2,  16.50, 1),
    ('GL100-BLK-L',  1,  14.00, 1), ('GL100-BLK-L',  2,  16.50, 1),
    ('GL100-BLK-XL', 1,  15.00, 1), ('GL100-BLK-XL', 2,  17.50, 1),
    ('PL001-BLK-01', 1,  17.10, 1), ('PL001-BLK-01', 2,  19.00, 1),
    ('PL001-BLK-06', 1,  93.60, 1), ('PL001-BLK-06', 2, 105.00, 1);
