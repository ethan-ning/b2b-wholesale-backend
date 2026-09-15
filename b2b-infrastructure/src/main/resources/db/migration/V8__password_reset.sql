-- Password reset by emailed link, for dealers and admins alike.
--
-- The token itself is never stored. Only its SHA-256 digest is, for the same reason a
-- password is not stored in the clear: whoever can read this table must not be able to
-- take over an account with what they find. The digest is deterministic rather than
-- salted because the row has to be found *by* the token a link carries — BCrypt cannot
-- be looked up.
--
-- One table for both audiences rather than two. The rules — single use, expiry, one live
-- link per account — are identical, and stating them twice is how they drift apart.

CREATE TABLE password_reset_token (
    id           BIGSERIAL PRIMARY KEY,
    -- DEALER or ADMIN. Dealers and admins are separate account tables, so subject_id is
    -- only meaningful alongside this; there is deliberately no foreign key.
    audience     VARCHAR(16)  NOT NULL,
    subject_id   BIGINT       NOT NULL,
    token_digest VARCHAR(64)  NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    -- Set when the link is spent. A used row is kept rather than deleted so a second
    -- click can be told the link is spent instead of simply "invalid".
    used_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- The lookup the reset endpoint performs, and the guarantee that two links can never
-- collide onto one row.
CREATE UNIQUE INDEX idx_password_reset_digest ON password_reset_token (token_digest);

-- Requesting a new link invalidates any earlier one for the same account; this is the
-- index that sweep reads.
CREATE INDEX idx_password_reset_subject ON password_reset_token (audience, subject_id);

-- Expired rows are swept on a schedule, not read.
CREATE INDEX idx_password_reset_expires ON password_reset_token (expires_at);
