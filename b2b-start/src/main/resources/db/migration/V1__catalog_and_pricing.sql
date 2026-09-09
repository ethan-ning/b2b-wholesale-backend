-- Catalog, pricing and the stock replica for the MVP lookup slice.
--
-- Two rules the schema encodes, both from ARCHITECTURE.md:
--   * a SKU's price and MAP are stated per SKU; there is no SPU-level row to inherit
--   * stock columns are a read replica of the ERP and are never written by the portal

CREATE TABLE category (
    id         BIGSERIAL PRIMARY KEY,
    name       TEXT   NOT NULL,
    slug       TEXT   NOT NULL UNIQUE,
    parent_id  BIGINT REFERENCES category (id),
    sort_order INT    NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE product (
    id                   BIGSERIAL PRIMARY KEY,
    spu_code             TEXT          NOT NULL UNIQUE,
    name                 TEXT          NOT NULL,
    brand                TEXT,
    description          TEXT,
    base_wholesale_price NUMERIC(10,2) NOT NULL,
    location_code        TEXT,
    -- 'Size' or 'Pack Qty'. NULL only for a single-SKU product.
    variant_axis         TEXT,
    attributes_json      TEXT,
    status               TEXT          NOT NULL DEFAULT 'DRAFT'
                             CHECK (status IN ('ACTIVE', 'DRAFT', 'ARCHIVED')),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_product_brand ON product (brand);
CREATE INDEX idx_product_status ON product (status);

CREATE TABLE product_variant (
    id              BIGSERIAL PRIMARY KEY,
    product_id      BIGINT        NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    sku             TEXT          NOT NULL UNIQUE,
    -- Value on the product's axis: 'M', 'XL', '6'. Matches the SKU code's suffix.
    variant_value   TEXT,
    -- Sizes are not lexically ordered (S < M < L < XL), so display order is explicit.
    sort_order      INT           NOT NULL DEFAULT 0,
    pack_quantity   INT           NOT NULL DEFAULT 1 CHECK (pack_quantity >= 1),
    -- Advertised price for ONE of this SKU: a garment, or a whole pack.
    map_price       NUMERIC(10,2),
    upc             VARCHAR(14),
    weight          NUMERIC(8,3),
    status          TEXT          NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'DISCONTINUED')),
    -- Replica of the ERP's numbers. Written only by the stock sync.
    available_stock INT           NOT NULL DEFAULT 0 CHECK (available_stock >= 0),
    incoming_stock  INT           NOT NULL DEFAULT 0 CHECK (incoming_stock >= 0),
    stock_synced_at TIMESTAMPTZ
);
CREATE INDEX idx_variant_product ON product_variant (product_id);

CREATE TABLE product_image (
    id         BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    url        TEXT   NOT NULL,
    alt_text   TEXT,
    sort_order INT    NOT NULL DEFAULT 0
);
CREATE INDEX idx_image_product ON product_image (product_id, sort_order);

CREATE TABLE product_category (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT  NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    category_id BIGINT  NOT NULL REFERENCES category (id),
    is_primary  BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (product_id, category_id)
);
CREATE INDEX idx_product_category_cat ON product_category (category_id);

CREATE TABLE customer_tier (
    id         BIGSERIAL PRIMARY KEY,
    name       TEXT NOT NULL UNIQUE,
    sort_order INT  NOT NULL DEFAULT 0
);

-- One row per SKU per tier. min_qty is pinned to 1 for the MVP: quantity-based pricing
-- is deferred, and keeping the column here means enabling it is an INSERT rather than a
-- migration that widens this unique key.
CREATE TABLE tier_price (
    id      BIGSERIAL PRIMARY KEY,
    sku     TEXT          NOT NULL REFERENCES product_variant (sku) ON DELETE CASCADE,
    tier_id BIGINT        NOT NULL REFERENCES customer_tier (id),
    price   NUMERIC(10,2) NOT NULL CHECK (price >= 0),
    min_qty INT           NOT NULL DEFAULT 1 CHECK (min_qty >= 1),
    UNIQUE (sku, tier_id, min_qty)
);
CREATE INDEX idx_tier_price_lookup ON tier_price (sku, tier_id, min_qty);

CREATE TABLE customer (
    id                   BIGSERIAL PRIMARY KEY,
    email                TEXT    NOT NULL UNIQUE,
    password_hash        TEXT    NOT NULL,
    name                 TEXT    NOT NULL,
    company_name         TEXT    NOT NULL,
    tier_id              BIGINT  NOT NULL REFERENCES customer_tier (id),
    phone                TEXT,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    status               TEXT    NOT NULL DEFAULT 'ACTIVE'
                             CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
