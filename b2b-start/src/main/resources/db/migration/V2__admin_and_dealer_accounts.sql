-- Admin accounts, and the seed data a fresh environment needs to be usable.
--
-- Dealer accounts (customer) and pricing tiers were created in V1; this adds the
-- back-office side and seeds the two tiers the pricing model assumes.

CREATE TABLE admin_user (
    id            BIGSERIAL PRIMARY KEY,
    email         TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    name          TEXT NOT NULL,
    role          TEXT NOT NULL DEFAULT 'ADMIN'
                      CHECK (role IN ('SUPER_ADMIN', 'ADMIN')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Dealer list is filtered by name, email and company; status narrows it.
CREATE INDEX idx_customer_status ON customer (status);
CREATE INDEX idx_customer_company ON customer (LOWER(company_name));

-- The two tiers the catalog's pricing is written against. Ids are referenced by
-- tier_price rows, so they are fixed here rather than left to insertion order.
INSERT INTO customer_tier (id, name, sort_order) VALUES
    (1, 'Gold', 1),
    (2, 'Silver', 2);
SELECT setval('customer_tier_id_seq', (SELECT MAX(id) FROM customer_tier));

-- Bootstrap admin for local development: admin@example.com / admin123, matching the
-- portal's mock so the frontend works against either. The hash is real BCrypt cost 12,
-- generated with the same encoder the application verifies against.
--
-- This password is in version control. Any environment beyond a laptop must change it.
INSERT INTO admin_user (email, password_hash, name, role) VALUES (
    'admin@example.com',
    '$2a$12$NUf6Dv9yqb.rWZJE7jiShu1Do4.iAmhdXSPVRARcl2Va/rL46xqGW',
    'System Admin',
    'SUPER_ADMIN'
);
