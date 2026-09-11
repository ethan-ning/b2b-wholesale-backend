-- An admin created by another admin holds a password they did not choose, exactly as a
-- dealer does. Same column, same meaning, and the same token restriction enforces it.
--
-- Defaults to FALSE so that admins who already exist are not locked out of a portal they
-- have been using; the flag is set going forward, when an account is created or reset.
ALTER TABLE admin_user
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
