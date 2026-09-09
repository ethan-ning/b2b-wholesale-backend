-- Categories are chosen at the second level of the path, not the leaf.
--
-- Sellfox's tree goes three deep — "供应商甲/重卡配件/轮毂盖" — and the leaves are the wrong
-- unit to choose from: 90 of them, most holding a handful of SKUs, and picking a product
-- line means ticking a dozen. A row here is now the first two segments of the path
-- ("供应商甲/重卡配件"), and selecting it takes everything beneath it.
--
-- Existing rows are leaves under the old meaning, so they are cleared rather than
-- migrated. The table is a discovery cache — the next sync rebuilds it — and the only
-- real loss is the selection, which has to be made again because it now means
-- something different.

DELETE FROM sellfox_category;

COMMENT ON TABLE sellfox_category IS
    'Second-level Sellfox category groups, discovered by a sync. One row per distinct '
    'first-two-segment path prefix; selecting one imports every commodity beneath it.';

COMMENT ON COLUMN sellfox_category.cid IS
    'The group key: the first two segments of the commodity''s fullCid, joined by "-". '
    'A one-level path contributes its single segment.';
