-- More local sample catalog. Local development ONLY — see V900.
--
-- A separate file rather than an edit to V900: that migration has already been applied,
-- and validate-on-migrate would reject a changed checksum. This applies cleanly to both
-- a fresh volume and a running one.
--
-- Chosen to exercise the cases the first three products did not:
--   * both variant axes, including a single-SKU product with no axis at all
--   * every product status, so the admin list filters have something to find
--   * every stock state: healthy, low, out of stock, and incoming
--   * a product with no tier prices, which falls through to list price
--   * a product with no image, and one filed under two categories
--   * enough rows to page past the default size of 10

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
    -- Archived: should not reach a dealer, and should be findable by the admin filter.
    (11, 'WH900-SLV', 'Alloy Wheel - Silver', 'RoadForge',
         'Cast alloy wheel, discontinued line.',
         145.00, 'E1-1', 'Size', '{"Finish":"Silver"}', 'ARCHIVED'),
    -- Draft, and deliberately left unpriced so pricing falls through to list price.
    (12, 'BR300-RED', 'Brake Caliper Cover - Red', 'StopTech',
         'Powder-coated caliper cover, awaiting pricing.',
         24.00, 'E2-1', 'Pack Qty', '{"Color":"Red"}', 'DRAFT');
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
