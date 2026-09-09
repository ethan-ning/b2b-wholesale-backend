-- Sample data for local development ONLY.
--
-- Applied because application-local.yml adds classpath:db/seed to Flyway's locations.
-- Production never lists that location, so none of this can reach it. The high version
-- number keeps it clear of real migrations; application-local.yml sets out-of-order so a
-- new real migration is still allowed to arrive "before" it.
--
-- Everything here has a password or a price in version control. That is the point — it
-- makes a fresh checkout usable — and it is also why it is confined to this location.

-- ─── Accounts ────────────────────────────────────────────────────────────
--
-- The bootstrap admin lives here rather than in a real migration. A migration that seeds
-- a known password runs in production too, and this one is published in a public repo.
-- Production gets its first admin by an explicit INSERT at deploy time; see README.
--
--   admin@example.com / admin123        (SUPER_ADMIN)
--   dealer1@example.com / dealer123     (Gold)
--   dealer2@example.com / dealer123     (Silver)
--
-- All BCrypt cost 12, generated with the encoder the application verifies against.
INSERT INTO admin_user (email, password_hash, name, role) VALUES (
    'admin@example.com',
    '$2a$12$NUf6Dv9yqb.rWZJE7jiShu1Do4.iAmhdXSPVRARcl2Va/rL46xqGW',
    'System Admin',
    'SUPER_ADMIN'
);

-- ─── from local sample catalog ───
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

-- ─── from more sample catalog ───
INSERT INTO category (id, name, slug, parent_id, sort_order) VALUES
    (31, 'Hand Tools', 'hand-tools', 1, 3),
    (32, 'Wheels',     'wheels',     1, 4),
    (33, 'Brakes',     'brakes',     1, 5);
SELECT setval('category_id_seq', (SELECT MAX(id) FROM category));

INSERT INTO product (id, spu_code, name, brand, description, base_wholesale_price, location_code, variant_axis, attributes_json, status) VALUES
    (4,  'GL100-BRN', 'Riding Gloves - Brown', 'RiderEdge',
         'Touchscreen-compatible leather riding gloves, brown.',
         18.00, 'C2-2', 'Size', '{"Color":"Brown","Material":"Genuine Leather"}', 'ACTIVE'),
    (5,  'JK400-BLK', 'Motorcycle Leather Jacket - Black', 'RiderEdge',
         'Premium cowhide leather motorcycle jacket, CE-rated armor pockets.',
         89.00, 'C1-1', 'Size', '{"Color":"Black","Material":"Cowhide Leather","CE_Armor":"Level 1"}', 'ACTIVE'),
    (6,  'JK400-BRN', 'Motorcycle Leather Jacket - Brown', 'RiderEdge',
         'Premium cowhide leather motorcycle jacket, brown.',
         92.00, 'C1-2', 'Size', '{"Color":"Brown","Material":"Cowhide Leather"}', 'ACTIVE'),
    (7,  'PL001-CHR', 'Muffler Extension Pipe - Chrome', 'ProLine',
         'Heavy-duty stainless steel muffler extension pipe, mirror chrome finish.',
         22.00, 'A1-2', 'Pack Qty', '{"Color":"Chrome","Material":"Stainless Steel"}', 'ACTIVE'),
    (8,  'EX100', 'Performance Exhaust Tip', 'TurboKing',
         '4-inch performance exhaust tip, universal fit.',
         14.50, 'A2-1', 'Pack Qty', '{"Diameter":"4 inch","Finish":"Polished"}', 'ACTIVE'),
    -- Single SKU, so no variant axis. The domain only requires one above a single SKU.
    (9,  'LT201-AMB', 'LED Light Bar - Amber', 'LumenPro',
         '20-inch LED light bar, amber lens for fog and dust.',
         32.00, 'B3-2', NULL, '{"Color":"Amber","Wattage":"120W"}', 'ACTIVE'),
    (10, 'TL500', 'Socket Wrench Set', 'GripMaster',
         '40-piece metric and SAE socket wrench set with case.',
         28.00, 'D1-1', 'Pack Qty', '{"Pieces":"40","Drive_Size":"3/8 inch"}', 'ACTIVE'),
    -- Deactivated: hidden from dealers, still findable by the admin filter.
    (11, 'WH900-SLV', 'Alloy Wheel - Silver', 'RoadForge',
         'Cast alloy wheel, discontinued line.',
         145.00, 'E1-1', 'Size', '{"Finish":"Silver"}', 'INACTIVE'),
    -- Deactivated, and deliberately left unpriced so pricing falls through to list price.
    (12, 'BR300-RED', 'Brake Caliper Cover - Red', 'StopTech',
         'Powder-coated caliper cover, awaiting pricing.',
         24.00, 'E2-1', 'Pack Qty', '{"Color":"Red"}', 'INACTIVE');
SELECT setval('product_id_seq', (SELECT MAX(id) FROM product));

-- sort_order is explicit throughout: S < M < L < XL is not lexical.
INSERT INTO product_variant (id, product_id, sku, variant_value, sort_order, pack_quantity, map_price, upc, weight, status, available_stock, incoming_stock, stock_synced_at) VALUES
    (10, 4, 'GL100-BRN-S',  'S',  0, 1,  36.99, '056789000011', 0.30, 'ACTIVE', 11,  0, NOW()),
    (11, 4, 'GL100-BRN-M',  'M',  1, 1,  36.99, '056789000012', 0.32, 'ACTIVE',  2, 12, NOW()),
    (12, 4, 'GL100-BRN-L',  'L',  2, 1,  36.99, '056789000013', 0.34, 'ACTIVE',  0,  0, NOW()),

    (13, 5, 'JK400-BLK-S',  'S',  0, 1, 179.99, '045678900001', 1.80, 'ACTIVE', 12,  0, NOW()),
    (14, 5, 'JK400-BLK-M',  'M',  1, 1, 179.99, '045678900002', 1.85, 'ACTIVE',  8, 20, NOW()),
    (15, 5, 'JK400-BLK-L',  'L',  2, 1, 179.99, '045678900003', 1.90, 'ACTIVE',  3,  0, NOW()),
    -- XL carries a wholesale premium, so its MAP moves with it.
    (16, 5, 'JK400-BLK-XL', 'XL', 3, 1, 189.99, '045678900004', 1.95, 'ACTIVE',  0, 24, NOW()),

    (17, 6, 'JK400-BRN-M',  'M',  0, 1, 184.99, '045678900011', 1.85, 'ACTIVE',  7,  0, NOW()),
    (18, 6, 'JK400-BRN-L',  'L',  1, 1, 184.99, '045678900012', 1.90, 'ACTIVE',  5,  0, NOW()),
    (19, 6, 'JK400-BRN-XL', 'XL', 2, 1, 194.99, '045678900013', 1.95, 'ACTIVE',  2, 10, NOW()),

    -- Pack SKUs: MAP is the whole pack, so a 6-pack of a $44.99 part advertises at 6x.
    (20, 7, 'PL001-CHR-01', '1',  0, 1,  44.99, '012345678911', 0.50, 'ACTIVE',  0, 30, NOW()),
    (21, 7, 'PL001-CHR-06', '6',  1, 6, 269.94, '012345678912', 3.00, 'ACTIVE',  2,  0, NOW()),

    (22, 8, 'EX100-01',     '1',  0, 1,  29.99, '023456789001', 0.40, 'ACTIVE', 18,  0, NOW()),
    (23, 8, 'EX100-02',     '2',  1, 2,  59.98, '023456789002', 0.80, 'ACTIVE',  7, 20, NOW()),
    (24, 8, 'EX100-12',     '12', 2, 12, 359.88, '023456789003', 4.80, 'ACTIVE',  1,  0, NOW()),

    (25, 9, 'LT201-AMB-01', '1',  0, 1,  64.99, '034567890011', 1.20, 'ACTIVE',  0,  0, NOW()),

    (26, 10, 'TL500-01',    '1',  0, 1,  55.99, '067890100001', 2.10, 'ACTIVE',  9,  0, NOW()),
    (27, 10, 'TL500-06',    '6',  1, 6, 335.94, '067890100002', 12.60,'ACTIVE',  2,  6, NOW()),

    (28, 11, 'WH900-SLV-17', '17', 0, 1, 289.99, '078901200001', 9.50, 'DISCONTINUED', 4, 0, NOW()),
    (29, 11, 'WH900-SLV-18', '18', 1, 1, 309.99, '078901200002', 10.20,'DISCONTINUED', 0, 0, NOW()),

    (30, 12, 'BR300-RED-01','1',  0, 1,  49.99, '089012300001', 0.60, 'ACTIVE', 15,  0, NOW()),
    (31, 12, 'BR300-RED-04','4',  1, 4, 199.96, '089012300002', 2.40, 'ACTIVE',  6,  0, NOW());
SELECT setval('product_variant_id_seq', (SELECT MAX(id) FROM product_variant));

-- Product 9 deliberately has no image, to exercise the placeholder path.
INSERT INTO product_image (product_id, url, alt_text, sort_order) VALUES
    (4,  'https://placehold.co/400x300/5d4037/ffffff?text=Gloves+Brown',  'Riding Gloves - Brown', 0),
    (5,  'https://placehold.co/400x300/111111/ffffff?text=Jacket+Black',  'Jacket - Black', 0),
    (5,  'https://placehold.co/400x300/222222/ffffff?text=Jacket+Detail', 'Jacket - detail', 1),
    (6,  'https://placehold.co/400x300/6d4c41/ffffff?text=Jacket+Brown',  'Jacket - Brown', 0),
    (7,  'https://placehold.co/400x300/c0c0c0/333333?text=Muffler+Chrome','Muffler - Chrome', 0),
    (8,  'https://placehold.co/400x300/888888/ffffff?text=Exhaust+Tip',   'Exhaust Tip', 0),
    (10, 'https://placehold.co/400x300/f57c00/ffffff?text=Socket+Set',    'Socket Wrench Set', 0),
    (11, 'https://placehold.co/400x300/9e9e9e/ffffff?text=Alloy+Wheel',   'Alloy Wheel', 0),
    (12, 'https://placehold.co/400x300/d32f2f/ffffff?text=Caliper+Cover', 'Caliper Cover', 0);

-- Product 8 sits in two categories, to exercise the primary flag.
INSERT INTO product_category (product_id, category_id, is_primary) VALUES
    (4,  22, TRUE),
    (5,  21, TRUE),
    (6,  21, TRUE),
    (7,  11, TRUE),
    (8,  11, TRUE),
    (8,  32, FALSE),
    (9,  12, TRUE),
    (10, 31, TRUE),
    (11, 32, TRUE),
    (12, 33, TRUE);

-- One row per SKU per tier. Price is what one of that SKU costs, so a pack row is the
-- whole pack. Product 12 is left out entirely: an unpriced SKU should resolve to list
-- price, and there should be a case in the data that proves it.
INSERT INTO tier_price (sku, tier_id, price, min_qty) VALUES
    ('GL100-BRN-S',  1,  14.40, 1), ('GL100-BRN-S',  2,  16.50, 1),
    ('GL100-BRN-M',  1,  14.40, 1), ('GL100-BRN-M',  2,  16.50, 1),
    ('GL100-BRN-L',  1,  14.40, 1), ('GL100-BRN-L',  2,  16.50, 1),

    ('JK400-BLK-S',  1,  62.00, 1), ('JK400-BLK-S',  2,  74.00, 1),
    ('JK400-BLK-M',  1,  69.50, 1), ('JK400-BLK-M',  2,  82.00, 1),
    ('JK400-BLK-L',  1,  69.50, 1), ('JK400-BLK-L',  2,  82.00, 1),
    ('JK400-BLK-XL', 1,  73.50, 1), ('JK400-BLK-XL', 2,  86.00, 1),

    ('JK400-BRN-M',  1,  71.75, 1), ('JK400-BRN-M',  2,  85.00, 1),
    ('JK400-BRN-L',  1,  71.75, 1), ('JK400-BRN-L',  2,  85.00, 1),
    ('JK400-BRN-XL', 1,  75.75, 1), ('JK400-BRN-XL', 2,  89.00, 1),

    ('PL001-CHR-01', 1,  19.80, 1), ('PL001-CHR-01', 2,  22.00, 1),
    ('PL001-CHR-06', 1, 106.80, 1), ('PL001-CHR-06', 2, 120.00, 1),

    ('EX100-01',     1,  12.75, 1), ('EX100-01',     2,  14.50, 1),
    ('EX100-02',     1,  24.50, 1), ('EX100-02',     2,  28.00, 1),
    ('EX100-12',     1, 129.00, 1), ('EX100-12',     2, 150.00, 1),

    ('LT201-AMB-01', 1,  27.50, 1), ('LT201-AMB-01', 2,  31.00, 1),

    ('TL500-01',     1,  24.50, 1), ('TL500-01',     2,  27.50, 1),
    ('TL500-06',     1, 129.00, 1), ('TL500-06',     2, 147.00, 1),

    ('WH900-SLV-17', 1, 210.00, 1), ('WH900-SLV-17', 2, 240.00, 1),
    ('WH900-SLV-18', 1, 228.00, 1), ('WH900-SLV-18', 2, 258.00, 1);

-- ─── from catalog breadth ───
INSERT INTO category (id, name, slug, parent_id, sort_order) VALUES
    (34, 'Luggage', 'luggage', 2, 3);
SELECT setval('category_id_seq', (SELECT MAX(id) FROM category));

INSERT INTO product (id, spu_code, name, brand, description, base_wholesale_price, location_code, variant_axis, attributes_json, status) VALUES
    (13, 'VS200-BLK', 'Leather Vest - Black', 'RiderEdge', NULL, 64.00, 'C1-3', 'Size', '{"Color":"Black","Material":"Cowhide Leather"}', 'ACTIVE'),
    (14, 'CH500-BLK', 'Riding Chaps - Black', 'RiderEdge', NULL, 78.00, 'C1-4', 'Size', '{"Color":"Black","Material":"Cowhide Leather"}', 'ACTIVE'),
    (15, 'GL200-BLK', 'Winter Riding Gloves - Black', 'RiderEdge', NULL, 26.00, 'C2-3', 'Size', '{"Color":"Black","Lining":"Thinsulate"}', 'ACTIVE'),
    (16, 'LT300-WHT', 'LED Spot Light - White', 'LumenPro', NULL, 21.00, 'B3-3', 'Pack Qty', '{"Wattage":"40W","IP_Rating":"IP67"}', 'ACTIVE'),
    (17, 'LT400-RED', 'Tail Light Kit - Red', 'LumenPro', NULL, 27.50, 'B3-4', 'Pack Qty', '{"Color":"Red"}', 'INACTIVE'),
    (18, 'EX200-SS', 'Slip-On Muffler - Stainless', 'TurboKing', NULL, 96.00, 'A2-2', 'Pack Qty', '{"Material":"Stainless Steel"}', 'ACTIVE'),
    (19, 'TL600', 'Torque Wrench 1/2 inch', 'GripMaster', NULL, 54.00, 'D1-2', NULL, '{"Drive_Size":"1/2 inch","Range":"20-200 Nm"}', 'ACTIVE'),
    (20, 'TL700', 'Precision Screwdriver Set', 'GripMaster', NULL, 16.00, 'D1-3', 'Pack Qty', '{"Pieces":"24"}', 'ACTIVE'),
    (21, 'WH901-BLK', 'Alloy Wheel - Matte Black', 'RoadForge', NULL, 158.00, 'E1-2', 'Size', '{"Finish":"Matte Black"}', 'ACTIVE'),
    (22, 'BR400-BLU', 'Brake Pad Set - Sintered', 'StopTech', NULL, 31.00, 'E2-2', 'Pack Qty', '{"Compound":"Sintered"}', 'ACTIVE'),
    (23, 'BR500-BLK', 'Braided Brake Line Kit', 'StopTech', NULL, 44.00, 'E2-3', NULL, '{"Length":"36 inch"}', 'INACTIVE'),
    (24, 'HG100-BLK', 'Helmet Bag - Black', 'RiderEdge', NULL, 19.00, 'C3-1', 'Pack Qty', '{"Material":"600D Nylon"}', 'ACTIVE');
SELECT setval('product_id_seq', (SELECT MAX(id) FROM product));

-- sort_order is explicit: sizes are not lexical, and pack rows read low to high.
INSERT INTO product_variant (id, product_id, sku, variant_value, sort_order, pack_quantity, map_price, upc, weight, status, available_stock, incoming_stock, stock_synced_at) VALUES
    (32, 13, 'VS200-BLK-S', 'S', 0, 1, 129.99, '090012300001', 0.9, 'ACTIVE', 9, 0, NOW()),
    (33, 13, 'VS200-BLK-M', 'M', 1, 1, 129.99, '090012300002', 0.95, 'ACTIVE', 6, 0, NOW()),
    (34, 13, 'VS200-BLK-L', 'L', 2, 1, 129.99, '090012300003', 1.0, 'ACTIVE', 3, 8, NOW()),
    (35, 13, 'VS200-BLK-XL', 'XL', 3, 1, 139.99, '090012300004', 1.05, 'ACTIVE', 0, 12, NOW()),
    (36, 14, 'CH500-BLK-M', 'M', 0, 1, 159.99, '090012300005', 1.4, 'ACTIVE', 5, 0, NOW()),
    (37, 14, 'CH500-BLK-L', 'L', 1, 1, 159.99, '090012300006', 1.45, 'ACTIVE', 4, 0, NOW()),
    (38, 14, 'CH500-BLK-XL', 'XL', 2, 1, 169.99, '090012300007', 1.5, 'ACTIVE', 1, 6, NOW()),
    (39, 15, 'GL200-BLK-M', 'M', 0, 1, 52.99, '090012300008', 0.4, 'ACTIVE', 13, 0, NOW()),
    (40, 15, 'GL200-BLK-L', 'L', 1, 1, 52.99, '090012300009', 0.42, 'ACTIVE', 2, 10, NOW()),
    (41, 16, 'LT300-WHT-01', '1', 0, 1, 42.99, '090012300010', 0.35, 'ACTIVE', 21, 0, NOW()),
    (42, 16, 'LT300-WHT-04', '4', 1, 4, 171.96, '090012300011', 1.4, 'ACTIVE', 4, 0, NOW()),
    (43, 17, 'LT400-RED-01', '1', 0, 1, 55.99, '090012300012', 0.5, 'ACTIVE', 8, 0, NOW()),
    (44, 17, 'LT400-RED-02', '2', 1, 2, 111.98, '090012300013', 1.0, 'ACTIVE', 0, 0, NOW()),
    (45, 18, 'EX200-SS-01', '1', 0, 1, 199.99, '090012300014', 3.2, 'ACTIVE', 6, 0, NOW()),
    (46, 18, 'EX200-SS-06', '6', 1, 6, 1199.94, '090012300015', 19.2, 'ACTIVE', 1, 3, NOW()),
    (47, 19, 'TL600-01', '1', 0, 1, 109.99, '090012300016', 2.4, 'ACTIVE', 12, 0, NOW()),
    (48, 20, 'TL700-01', '1', 0, 1, 32.99, '090012300017', 0.3, 'ACTIVE', 30, 0, NOW()),
    (49, 20, 'TL700-12', '12', 1, 12, 395.88, '090012300018', 3.6, 'ACTIVE', 2, 12, NOW()),
    (50, 21, 'WH901-BLK-17', '17', 0, 1, 319.99, '090012300019', 9.4, 'ACTIVE', 6, 0, NOW()),
    (51, 21, 'WH901-BLK-18', '18', 1, 1, 339.99, '090012300020', 10.1, 'ACTIVE', 3, 4, NOW()),
    (52, 21, 'WH901-BLK-19', '19', 2, 1, 359.99, '090012300021', 10.8, 'ACTIVE', 0, 8, NOW()),
    (53, 22, 'BR400-BLU-01', '1', 0, 1, 64.99, '090012300022', 0.7, 'ACTIVE', 17, 0, NOW()),
    (54, 22, 'BR400-BLU-04', '4', 1, 4, 259.96, '090012300023', 2.8, 'ACTIVE', 4, 0, NOW()),
    (55, 23, 'BR500-BLK-01', '1', 0, 1, 89.99, '090012300024', 0.8, 'DISCONTINUED', 2, 0, NOW()),
    (56, 24, 'HG100-BLK-01', '1', 0, 1, 39.99, '090012300025', 0.5, 'ACTIVE', 14, 0, NOW()),
    (57, 24, 'HG100-BLK-06', '6', 1, 6, 239.94, '090012300026', 3.0, 'ACTIVE', 0, 18, NOW());
SELECT setval('product_variant_id_seq', (SELECT MAX(id) FROM product_variant));

-- LT400-RED has no image, exercising the placeholder path.
INSERT INTO product_image (product_id, url, alt_text, sort_order) VALUES
    (13, 'https://placehold.co/400x300?text=VS200-BLK', 'Leather Vest - Black', 0),
    (14, 'https://placehold.co/400x300?text=CH500-BLK', 'Riding Chaps - Black', 0),
    (15, 'https://placehold.co/400x300?text=GL200-BLK', 'Winter Riding Gloves - Black', 0),
    (16, 'https://placehold.co/400x300?text=LT300-WHT', 'LED Spot Light - White', 0),
    (18, 'https://placehold.co/400x300?text=EX200-SS', 'Slip-On Muffler - Stainless', 0),
    (19, 'https://placehold.co/400x300?text=TL600', 'Torque Wrench 1/2 inch', 0),
    (20, 'https://placehold.co/400x300?text=TL700', 'Precision Screwdriver Set', 0),
    (21, 'https://placehold.co/400x300?text=WH901-BLK', 'Alloy Wheel - Matte Black', 0),
    (22, 'https://placehold.co/400x300?text=BR400-BLU', 'Brake Pad Set - Sintered', 0),
    (23, 'https://placehold.co/400x300?text=BR500-BLK', 'Braided Brake Line Kit', 0),
    (24, 'https://placehold.co/400x300?text=HG100-BLK', 'Helmet Bag - Black', 0);

INSERT INTO product_category (product_id, category_id, is_primary) VALUES
    (13, 21, TRUE),
    (14, 21, TRUE),
    (15, 22, TRUE),
    (16, 12, TRUE),
    (17, 12, TRUE),
    (18, 11, TRUE),
    (19, 31, TRUE),
    (20, 31, TRUE),
    (21, 32, TRUE),
    (22, 33, TRUE),
    (23, 33, TRUE),
    (24, 34, TRUE),
    (24, 22, FALSE);

-- LT400-RED is deliberately unpriced: an unpriced SKU must resolve to list price,
-- and the data should contain a case that proves it.
INSERT INTO tier_price (sku, tier_id, price, min_qty) VALUES
    ('VS200-BLK-S', 1, 51.39, 1), ('VS200-BLK-S', 2, 60.46, 1),
    ('VS200-BLK-M', 1, 51.39, 1), ('VS200-BLK-M', 2, 60.46, 1),
    ('VS200-BLK-L', 1, 51.39, 1), ('VS200-BLK-L', 2, 60.46, 1),
    ('VS200-BLK-XL', 1, 55.34, 1), ('VS200-BLK-XL', 2, 65.11, 1),
    ('CH500-BLK-M', 1, 63.25, 1), ('CH500-BLK-M', 2, 74.41, 1),
    ('CH500-BLK-L', 1, 63.25, 1), ('CH500-BLK-L', 2, 74.41, 1),
    ('CH500-BLK-XL', 1, 67.21, 1), ('CH500-BLK-XL', 2, 79.07, 1),
    ('GL200-BLK-M', 1, 20.95, 1), ('GL200-BLK-M', 2, 24.65, 1),
    ('GL200-BLK-L', 1, 20.95, 1), ('GL200-BLK-L', 2, 24.65, 1),
    ('LT300-WHT-01', 1, 17.00, 1), ('LT300-WHT-01', 2, 20.00, 1),
    ('LT300-WHT-04', 1, 67.98, 1), ('LT300-WHT-04', 2, 79.98, 1),
    ('EX200-SS-01', 1, 79.07, 1), ('EX200-SS-01', 2, 93.02, 1),
    ('EX200-SS-06', 1, 474.39, 1), ('EX200-SS-06', 2, 558.11, 1),
    ('TL600-01', 1, 43.49, 1), ('TL600-01', 2, 51.16, 1),
    ('TL700-01', 1, 13.04, 1), ('TL700-01', 2, 15.34, 1),
    ('TL700-12', 1, 156.51, 1), ('TL700-12', 2, 184.13, 1),
    ('WH901-BLK-17', 1, 126.51, 1), ('WH901-BLK-17', 2, 148.83, 1),
    ('WH901-BLK-18', 1, 134.41, 1), ('WH901-BLK-18', 2, 158.13, 1),
    ('WH901-BLK-19', 1, 142.32, 1), ('WH901-BLK-19', 2, 167.44, 1),
    ('BR400-BLU-01', 1, 25.70, 1), ('BR400-BLU-01', 2, 30.23, 1),
    ('BR400-BLU-04', 1, 102.77, 1), ('BR400-BLU-04', 2, 120.91, 1),
    ('BR500-BLK-01', 1, 35.58, 1), ('BR500-BLK-01', 2, 41.86, 1),
    ('HG100-BLK-01', 1, 15.81, 1), ('HG100-BLK-01', 2, 18.60, 1),
    ('HG100-BLK-06', 1, 94.86, 1), ('HG100-BLK-06', 2, 111.60, 1);

-- ─── from local dealer accounts ───
INSERT INTO customer (email, password_hash, name, company_name, tier_id, phone, must_change_password, status) VALUES
    ('dealer1@example.com', '$2a$12$eG7IytiuhsJ8iKn/083kI.gezVdnfcfj0k7y4g/44w1.ph/ccR8s6',
     'Gold Dealer', 'Northgate Truck Supply', 1, '555-0101', false, 'ACTIVE'),
    ('dealer2@example.com', '$2a$12$eG7IytiuhsJ8iKn/083kI.gezVdnfcfj0k7y4g/44w1.ph/ccR8s6',
     'Silver Dealer', 'Cross Creek Auto', 2, '555-0102', false, 'ACTIVE')
ON CONFLICT (email) DO NOTHING;
