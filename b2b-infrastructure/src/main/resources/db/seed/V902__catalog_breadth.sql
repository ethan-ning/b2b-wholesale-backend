-- Third batch of local sample catalog. Local development ONLY — see V900.
--
-- Seeds accumulate as new versioned files rather than edits to existing ones: an applied
-- migration's checksum is fixed, and validate-on-migrate rejects a change. Each batch is
-- therefore additive and safe against a database that already has the earlier ones.
--
-- Brings the catalog to 24 products / 55 SKUs — enough to page past the admin list's
-- default of 10 and to give the dealer search something to narrow.

INSERT INTO category (id, name, slug, parent_id, sort_order) VALUES
    (34, 'Luggage', 'luggage', 2, 3);
SELECT setval('category_id_seq', (SELECT MAX(id) FROM category));

INSERT INTO product (id, spu_code, name, brand, description, base_wholesale_price, location_code, variant_axis, attributes_json, status) VALUES
    (13, 'VS200-BLK', 'Leather Vest - Black', 'RiderEdge', NULL, 64.00, 'C1-3', 'Size', '{"Color":"Black","Material":"Cowhide Leather"}', 'ACTIVE'),
    (14, 'CH500-BLK', 'Riding Chaps - Black', 'RiderEdge', NULL, 78.00, 'C1-4', 'Size', '{"Color":"Black","Material":"Cowhide Leather"}', 'ACTIVE'),
    (15, 'GL200-BLK', 'Winter Riding Gloves - Black', 'RiderEdge', NULL, 26.00, 'C2-3', 'Size', '{"Color":"Black","Lining":"Thinsulate"}', 'ACTIVE'),
    (16, 'LT300-WHT', 'LED Spot Light - White', 'LumenPro', NULL, 21.00, 'B3-3', 'Pack Qty', '{"Wattage":"40W","IP_Rating":"IP67"}', 'ACTIVE'),
    (17, 'LT400-RED', 'Tail Light Kit - Red', 'LumenPro', NULL, 27.50, 'B3-4', 'Pack Qty', '{"Color":"Red"}', 'DRAFT'),
    (18, 'EX200-SS', 'Slip-On Muffler - Stainless', 'TurboKing', NULL, 96.00, 'A2-2', 'Pack Qty', '{"Material":"Stainless Steel"}', 'ACTIVE'),
    (19, 'TL600', 'Torque Wrench 1/2 inch', 'GripMaster', NULL, 54.00, 'D1-2', NULL, '{"Drive_Size":"1/2 inch","Range":"20-200 Nm"}', 'ACTIVE'),
    (20, 'TL700', 'Precision Screwdriver Set', 'GripMaster', NULL, 16.00, 'D1-3', 'Pack Qty', '{"Pieces":"24"}', 'ACTIVE'),
    (21, 'WH901-BLK', 'Alloy Wheel - Matte Black', 'RoadForge', NULL, 158.00, 'E1-2', 'Size', '{"Finish":"Matte Black"}', 'ACTIVE'),
    (22, 'BR400-BLU', 'Brake Pad Set - Sintered', 'StopTech', NULL, 31.00, 'E2-2', 'Pack Qty', '{"Compound":"Sintered"}', 'ACTIVE'),
    (23, 'BR500-BLK', 'Braided Brake Line Kit', 'StopTech', NULL, 44.00, 'E2-3', NULL, '{"Length":"36 inch"}', 'ARCHIVED'),
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
