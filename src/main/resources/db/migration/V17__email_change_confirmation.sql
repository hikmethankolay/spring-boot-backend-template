-- A user's new email address waits here until the link sent to it is opened; only then does it replace email. So a
-- mistyped address never becomes the one password resets go to. Nullable, for the previous version during a rolling
-- update. The link's token is an account token of the new type EMAIL_CHANGE.
ALTER TABLE users ADD COLUMN pending_email VARCHAR(254);
-- Anonymous resend requests look users up by it; most rows have none, so only those that do are indexed.
CREATE INDEX ix_users_pending_email ON users (pending_email) WHERE pending_email IS NOT NULL;

ALTER TABLE account_tokens DROP CONSTRAINT ck_account_tokens_type;
ALTER TABLE account_tokens ADD CONSTRAINT ck_account_tokens_type
    CHECK (type IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET', 'EMAIL_CHANGE'));
