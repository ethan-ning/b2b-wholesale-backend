-- Sellfox integration: what to import, what was imported, and what each run did.
--
-- Sellfox is the system of record for what a product is and how many there are. These
-- tables hold the three things the portal needs on top of that: the scope an admin has
-- chosen, the provenance of each imported SKU, and the history of every sync run.

-- ─── Discovered scope ────────────────────────────────────────────────────
--
-- Sellfox exposes no category endpoint, so categories are learned from the commodity
-- scan itself: every commodity carries `fullCid` ("100010-100020-100030-") and
-- `fullName` ("供应商甲/重卡配件/轮毂盖"), which together describe the whole path. A scan
-- refreshes this table; the admin then ticks what to import. Nothing is imported from
-- a category until it is selected, so a first run is safe by construction.
CREATE TABLE sellfox_category (
    cid              TEXT PRIMARY KEY,          -- leaf category id
    full_cid         TEXT NOT NULL,             -- full path of ids, as Sellfox reports it
    full_name        TEXT NOT NULL,             -- full path of names, for display
    commodity_count  INT NOT NULL DEFAULT 0,    -- what the last scan saw here
    selected         BOOLEAN NOT NULL DEFAULT FALSE,
    last_seen_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sellfox_category_selected ON sellfox_category (selected);

-- Warehouses come from Sellfox's own list endpoint, refreshed the same way. Stock for a
-- SKU is summed across the selected warehouses: our product_variant carries one
-- available_stock figure, and a dealer only cares whether it can ship.
CREATE TABLE sellfox_warehouse (
    warehouse_id   BIGINT PRIMARY KEY,
    name           TEXT NOT NULL,
    type           INT,                         -- Sellfox: 0 default, 1 domestic, 2 FBA, 3 overseas
    selected       BOOLEAN NOT NULL DEFAULT FALSE,
    last_seen_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sellfox_warehouse_selected ON sellfox_warehouse (selected);

-- ─── Provenance ──────────────────────────────────────────────────────────
--
-- One row per Sellfox SKU we have imported. Our product_variant.sku IS the Sellfox
-- commoditySku — this catalog is sourced from Sellfox, so there is nothing to reconcile
-- and no mapping to drift. What this table adds is where the SKU came from and how it
-- was grouped, which is what makes re-scoping and disappearance detection possible.
CREATE TABLE sellfox_sku_link (
    sellfox_sku          TEXT PRIMARY KEY REFERENCES product_variant (sku) ON DELETE CASCADE,
    commodity_id         TEXT NOT NULL,
    full_cid             TEXT NOT NULL,
    -- The single-unit SKU this one packs, from Sellfox's childSkus. NULL when this SKU
    -- is itself the base. Packs and their base share an SPU; this records which is which.
    base_sellfox_sku     TEXT,
    last_seen_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sellfox_sku_link_cid ON sellfox_sku_link (full_cid);
CREATE INDEX idx_sellfox_sku_link_base ON sellfox_sku_link (base_sellfox_sku);

-- ─── Run history ─────────────────────────────────────────────────────────
--
-- Every run, scheduled or manual, lands here — including the ones that failed. A sync
-- that silently does nothing is indistinguishable from a sync that is not running, and
-- the difference matters when a dealer is looking at stale stock.
CREATE TABLE sellfox_sync_run (
    id               BIGSERIAL PRIMARY KEY,
    job              TEXT NOT NULL CHECK (job IN ('CATALOG', 'INVENTORY')),
    trigger_source   TEXT NOT NULL CHECK (trigger_source IN ('SCHEDULED', 'MANUAL')),
    status           TEXT NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    -- Who pressed the button. NULL for scheduled runs.
    triggered_by     TEXT,
    started_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at      TIMESTAMPTZ,
    records_read     INT NOT NULL DEFAULT 0,    -- rows Sellfox returned
    records_written  INT NOT NULL DEFAULT 0,    -- rows that changed something here
    records_skipped  INT NOT NULL DEFAULT 0,    -- out of scope, or nothing to match
    error_message    TEXT,
    -- Free-form line of what the run actually did, for the admin's run list.
    summary          TEXT
);
CREATE INDEX idx_sellfox_run_started ON sellfox_sync_run (started_at DESC);
CREATE INDEX idx_sellfox_run_job ON sellfox_sync_run (job, started_at DESC);

-- A product imported from Sellfox is not editable in the same way as one keyed in by
-- hand: its identity is Sellfox's. This flag is what the admin form reads to decide
-- which fields to render read-only.
ALTER TABLE product ADD COLUMN source TEXT NOT NULL DEFAULT 'PORTAL'
    CHECK (source IN ('PORTAL', 'SELLFOX'));
