-- The whole schema, in one script.
--
-- Collapsed from the eight migrations the first draft accumulated. Nothing is deployed
-- yet, so there is no history worth preserving and no ALTER worth reading: what the
-- tables are is easier to follow than how they got that way.
--
-- The decisions the collapsed migrations carried are kept as comments, because those are
-- the part that is still useful.

-- ─── Merchandising taxonomy ──────────────────────────────────────────────
--
-- Ours, not the ERP's. What a warehouse system files a part under is rarely how a dealer
-- looks for it. Capped at three levels in the domain (Category.MAX_DEPTH) rather than by
-- a constraint here: a row's depth is a fact about its ancestors, and checking it in SQL
-- means a recursive CTE on every insert.
CREATE TABLE category (
    id          BIGSERIAL PRIMARY KEY,
    name        TEXT NOT NULL,
    slug        TEXT NOT NULL UNIQUE,
    parent_id   BIGINT REFERENCES category (id),
    sort_order  INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_category_parent ON category (parent_id);

-- ─── Catalog ─────────────────────────────────────────────────────────────
--
-- A product is a style or colour; its SKUs vary along one axis, size for apparel and pack
-- quantity for parts. Two statuses, not three: products arrive from the ERP already real
-- so nothing is ever draft, and "archived" would be a second name for inactive.
CREATE TABLE product (
    id                    BIGSERIAL PRIMARY KEY,
    spu_code              TEXT NOT NULL UNIQUE,
    name                  TEXT NOT NULL,
    brand                 TEXT,
    description           TEXT,
    -- List price. Only reached when a SKU has no tier price at all.
    base_wholesale_price  NUMERIC(10, 2) NOT NULL,
    location_code         TEXT,
    variant_axis          TEXT,
    -- Display-only key/value bag. Seeded from the ERP at import, ours afterwards.
    attributes_json       TEXT,
    status                TEXT NOT NULL DEFAULT 'ACTIVE'
                              CHECK (status IN ('ACTIVE', 'INACTIVE')),
    -- Where this row's identity comes from. A sync never touches a PORTAL row, and the
    -- admin form reads this to decide which fields render read-only.
    source                TEXT NOT NULL DEFAULT 'PORTAL'
                              CHECK (source IN ('PORTAL', 'SELLFOX')),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_product_status ON product (status);
CREATE INDEX idx_product_source ON product (source);
CREATE INDEX idx_product_name ON product (LOWER(name));

-- One purchasable unit. A pack SKU's money is the whole pack, not one of its contents,
-- so pack_quantity is what makes a 6-pack comparable to a single.
CREATE TABLE product_variant (
    id               BIGSERIAL PRIMARY KEY,
    product_id       BIGINT NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    sku              TEXT NOT NULL UNIQUE,
    -- Value on the parent's axis — "M", "XL", "6".
    variant_value    TEXT,
    -- Sizes are not lexically ordered, so display order is stored rather than derived.
    sort_order       INT NOT NULL DEFAULT 0,
    pack_quantity    INT NOT NULL DEFAULT 1 CHECK (pack_quantity >= 1),
    -- Advertised price for one of this SKU. Never inherited from the product: a pack's
    -- MAP scales with its quantity, so there is no SPU-level figure to inherit from.
    map_price        NUMERIC(10, 2),
    upc              VARCHAR(14),
    weight           NUMERIC(8, 3),
    status           TEXT NOT NULL DEFAULT 'ACTIVE'
                         CHECK (status IN ('ACTIVE', 'DISCONTINUED')),
    -- Owned by the ERP. No portal path writes these.
    available_stock  INT NOT NULL DEFAULT 0 CHECK (available_stock >= 0),
    incoming_stock   INT NOT NULL DEFAULT 0 CHECK (incoming_stock >= 0),
    stock_synced_at  TIMESTAMPTZ
);
CREATE INDEX idx_variant_product ON product_variant (product_id);

CREATE TABLE product_image (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    url         TEXT NOT NULL,
    alt_text    TEXT,
    sort_order  INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_image_product ON product_image (product_id);

-- A product may sit in several categories; at most one of them is primary. At most, not
-- exactly: deleting a category unfiles its products, and one whose only category was the
-- deleted one is left filed nowhere rather than deleted.
CREATE TABLE product_category (
    id           BIGSERIAL PRIMARY KEY,
    product_id   BIGINT NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    category_id  BIGINT NOT NULL REFERENCES category (id),
    is_primary   BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (product_id, category_id)
);
CREATE INDEX idx_product_category_category ON product_category (category_id);

-- ─── Dealers and pricing ─────────────────────────────────────────────────

CREATE TABLE customer_tier (
    id          BIGSERIAL PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    sort_order  INT NOT NULL DEFAULT 0
);

CREATE TABLE customer (
    id                    BIGSERIAL PRIMARY KEY,
    email                 TEXT NOT NULL UNIQUE,
    password_hash         TEXT NOT NULL,
    name                  TEXT NOT NULL,
    company_name          TEXT NOT NULL,
    tier_id               BIGINT NOT NULL REFERENCES customer_tier (id),
    phone                 TEXT,
    -- An admin-created dealer starts here; the token they get reaches one endpoint.
    must_change_password  BOOLEAN NOT NULL DEFAULT TRUE,
    status                TEXT NOT NULL DEFAULT 'ACTIVE'
                              CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_customer_status ON customer (status);
CREATE INDEX idx_customer_company ON customer (LOWER(company_name));

-- Price per SKU per tier. Keyed on the SKU rather than the variant id so pricing survives
-- a regroup that moves the SKU to a different product.
--
-- min_qty is always 1 in the MVP — quantity-based pricing is deferred — but the column is
-- carried so enabling it stays an INSERT rather than a schema change.
CREATE TABLE tier_price (
    id       BIGSERIAL PRIMARY KEY,
    sku      TEXT NOT NULL REFERENCES product_variant (sku) ON DELETE CASCADE,
    tier_id  BIGINT NOT NULL REFERENCES customer_tier (id),
    price    NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    min_qty  INT NOT NULL DEFAULT 1 CHECK (min_qty >= 1),
    UNIQUE (sku, tier_id, min_qty)
);
CREATE INDEX idx_tier_price_sku ON tier_price (sku);

-- ─── Back office ─────────────────────────────────────────────────────────

CREATE TABLE admin_user (
    id             BIGSERIAL PRIMARY KEY,
    email          TEXT NOT NULL UNIQUE,
    password_hash  TEXT NOT NULL,
    name           TEXT NOT NULL,
    role           TEXT NOT NULL DEFAULT 'ADMIN'
                       CHECK (role IN ('SUPER_ADMIN', 'ADMIN')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- The two tiers the pricing model is written against. Ids are referenced by tier_price
-- rows, so they are fixed here rather than left to insertion order.
INSERT INTO customer_tier (id, name, sort_order) VALUES
    (1, 'Gold', 1),
    (2, 'Silver', 2);
SELECT setval('customer_tier_id_seq', (SELECT MAX(id) FROM customer_tier));

-- ─── ERP integration ─────────────────────────────────────────────────────
--
-- Sellfox is the system of record for what a product is and how many there are. These
-- tables hold what the portal needs on top of that: the scope an admin chose, the facts
-- a sync recorded, and the history of every run.

-- Sellfox exposes no category endpoint, so categories are learned from the commodity scan
-- itself — every commodity carries the full path of ids and names. A row here is a
-- second-level group ("供应商甲/重卡配件"); selecting it imports everything beneath it.
-- The leaves are the wrong unit to choose from: ninety of them against thirty-eight
-- groups, most holding a handful of SKUs.
CREATE TABLE sellfox_category (
    -- The first two segments of the commodity's fullCid, joined by "-". A one-level path
    -- contributes its single segment.
    cid              TEXT PRIMARY KEY,
    full_cid         TEXT NOT NULL,
    full_name        TEXT NOT NULL,
    commodity_count  INT NOT NULL DEFAULT 0,
    selected         BOOLEAN NOT NULL DEFAULT FALSE,
    last_seen_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sellfox_category_selected ON sellfox_category (selected);

-- Stock for a SKU is summed across the selected warehouses: a variant carries one
-- available figure and a dealer only asks whether it can ship.
CREATE TABLE sellfox_warehouse (
    warehouse_id  BIGINT PRIMARY KEY,
    name          TEXT NOT NULL,
    type          INT,   -- Sellfox: 0 default, 1 domestic, 2 FBA, 3 overseas
    selected      BOOLEAN NOT NULL DEFAULT FALSE,
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sellfox_warehouse_selected ON sellfox_warehouse (selected);

-- What a sync learned about one in-scope SKU, verbatim.
--
-- These are the *inputs* to SPU grouping, never its results: the declared SPU, the
-- declared pack child and quantity, the name and weight. Grouping reads only this table,
-- so it can be recomputed without calling Sellfox — and so it can never come to depend on
-- the answer a previous run gave.
--
-- Deliberately no foreign key to product_variant. A sync records these before any product
-- exists; the variant rows are what the regroup step builds *from* them.
CREATE TABLE sellfox_sku_link (
    sellfox_sku       TEXT PRIMARY KEY,
    commodity_id      TEXT NOT NULL,
    full_cid          TEXT NOT NULL,
    commodity_name    TEXT NOT NULL DEFAULT '',
    -- Sellfox's own SPU, on the fraction of rows where someone filled it in.
    declared_spu      TEXT,
    -- The single-unit SKU this one packs, and how many of it. Null when it is the base.
    base_sellfox_sku  TEXT,
    base_quantity     INT,
    weight_grams      NUMERIC(12, 3),
    last_seen_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sellfox_sku_link_cid ON sellfox_sku_link (full_cid);
CREATE INDEX idx_sellfox_sku_link_base ON sellfox_sku_link (base_sellfox_sku);

-- Every run, scheduled or manual, lands here — including the ones that failed, and the
-- ones still going. A row is written before the work starts: a run that dies halfway
-- would otherwise leave no trace, and "no row" would mean both "never ran" and "crashed".
--
-- mode says how deep a run went, not which job it was. There is one scope and one place
-- to set it; FULL pages the whole catalog, INVENTORY reads stock only, and REGROUP
-- recomputes the grouping from sellfox_sku_link without calling Sellfox at all.
CREATE TABLE sellfox_sync_run (
    id               BIGSERIAL PRIMARY KEY,
    mode             TEXT NOT NULL CHECK (mode IN ('FULL', 'INVENTORY', 'REGROUP')),
    trigger_source   TEXT NOT NULL
                         CHECK (trigger_source IN ('SCHEDULED', 'MANUAL', 'SCOPE_CHANGE')),
    status           TEXT NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    -- Who pressed the button. NULL for scheduled runs.
    triggered_by     TEXT,
    started_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at      TIMESTAMPTZ,
    records_read     INT NOT NULL DEFAULT 0,
    records_written  INT NOT NULL DEFAULT 0,
    records_skipped  INT NOT NULL DEFAULT 0,
    error_message    TEXT,
    summary          TEXT
);
CREATE INDEX idx_sellfox_run_started ON sellfox_sync_run (started_at DESC);
CREATE INDEX idx_sellfox_run_mode ON sellfox_sync_run (mode, started_at DESC);
