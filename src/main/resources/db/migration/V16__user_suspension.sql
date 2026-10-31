-- When an administrator suspended the account; NULL while it is active. A suspended user cannot log in and their
-- sessions have ended, but their data is kept, unlike a deletion. Nullable, so the previous version keeps working
-- against this schema during a rolling update (it ignores the column).
ALTER TABLE users ADD COLUMN suspended_at TIMESTAMPTZ;
