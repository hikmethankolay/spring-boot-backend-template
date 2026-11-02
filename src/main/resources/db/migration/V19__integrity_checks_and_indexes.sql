-- Rules the application always kept, now also enforced by the database, and indexes that match the queries that run.
--
-- Everything runs in Flyway's single transaction with plain CREATE INDEX: the tables are small in a template, and a
-- failure rolls everything back. CREATE INDEX blocks writes to its table (ADD CONSTRAINT also blocks reads) until the
-- commit. On a large installation (millions of audit events or queued emails), raise DB_STATEMENT_TIMEOUT for the
-- migration Job, or run the CREATE INDEX statements by hand beforehand as CREATE INDEX CONCURRENTLY with the same
-- names (IF NOT EXISTS below then skips them), and move VALIDATE CONSTRAINT to a separate migration so its scan does
-- not run under the ADD CONSTRAINT lock.

-- Emails are stored in lower case (UserService and AccountService normalise them), and logins look them up in lower
-- case, so a mixed-case row could never be found. Rows written before that rule (e.g. inserted by hand) are lowered
-- unless another account already has the lower-case address; such a pair stops the migration at VALIDATE, and must be
-- resolved by hand (rename or delete one of the two accounts).
UPDATE users u
SET email = lower(u.email)
WHERE u.email <> lower(u.email)
  AND NOT EXISTS (SELECT 1 FROM users o WHERE o.id <> u.id AND lower(o.email) = lower(u.email));

UPDATE users SET pending_email = lower(pending_email) WHERE pending_email <> lower(pending_email);

ALTER TABLE users ADD CONSTRAINT ck_users_email_lower CHECK (email = lower(email)) NOT VALID;
ALTER TABLE users VALIDATE CONSTRAINT ck_users_email_lower;

ALTER TABLE users ADD CONSTRAINT ck_users_pending_email_lower CHECK (pending_email = lower(pending_email)) NOT VALID;
ALTER TABLE users VALIDATE CONSTRAINT ck_users_pending_email_lower;

-- Usernames never contain '@' (ValidUsername) and emails always do, so findByUsernameOrEmail matches one row at most.
ALTER TABLE users ADD CONSTRAINT ck_users_username_no_at CHECK (strpos(username, '@') = 0) NOT VALID;
ALTER TABLE users VALIDATE CONSTRAINT ck_users_username_no_at;

-- pending_email stays non-unique on purpose: two users may wait for the same address and whoever opens their link
-- first gets it; a unique index would let one user block another's change and tell them it was requested.

-- The outbox poll: WHERE next_attempt_at <= :now ORDER BY (broadcast_id IS NOT NULL), id LIMIT n FOR UPDATE SKIP
-- LOCKED. The leading keys match the ORDER BY, so the scan stops after n due rows without sorting a large broadcast;
-- next_attempt_at as the last key lets emails waiting for a retry be skipped in the index, without reading their rows.
-- It replaces V15's (broadcast_id IS NOT NULL), id, its prefix. V4's next_attempt_at index stays, for the planner to
-- use while almost nothing is due (SMTP down, everything in backoff).
CREATE INDEX IF NOT EXISTS ix_email_outbox_due ON email_outbox ((broadcast_id IS NOT NULL), id, next_attempt_at);
DROP INDEX ix_email_outbox_send_order;

-- A user's security events, newest first (SecurityEvents; read backwards for oldest first). Replaces the user_id
-- index, its prefix, which made each page sort all the user's events.
CREATE INDEX IF NOT EXISTS ix_audit_events_user_occurred ON audit_events (user_id, occurred_at DESC, id DESC);
DROP INDEX ix_audit_events_user_id;

-- Deleting a user sets created_by to null on their broadcasts (ON DELETE SET NULL); without an index every user
-- deletion scans the whole broadcast history. Rows without a sender are never looked up, so they are not indexed.
CREATE INDEX IF NOT EXISTS ix_mail_broadcasts_created_by ON mail_broadcasts (created_by) WHERE created_by IS NOT NULL;
