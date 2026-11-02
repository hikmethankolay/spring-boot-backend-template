-- An email given up after app.mail.max-attempts stays in the outbox as a dead letter (failed_at set) instead of being
-- deleted, so it can be re-queued after a long SMTP outage. The dispatcher skips it; it is deleted after
-- app.mail.dead-letter-retention.
ALTER TABLE email_outbox ADD COLUMN failed_at TIMESTAMPTZ;
DROP INDEX ix_email_outbox_send_order;
CREATE INDEX ix_email_outbox_send_order ON email_outbox ((broadcast_id IS NOT NULL), id) WHERE failed_at IS NULL;
CREATE INDEX ix_email_outbox_failed_at ON email_outbox (failed_at) WHERE failed_at IS NOT NULL;

-- Progress of a broadcast being queued, saved with each batch: the last user id queued and when. A broadcast whose
-- progress stopped (its instance crashed or was stopped) is resumed after the last user id by another run.
ALTER TABLE mail_broadcasts ADD COLUMN last_user_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE mail_broadcasts ADD COLUMN progress_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Broadcasts interrupted before this version have no progress to resume from: close them as partial, so they are not
-- queued again from the first user.
UPDATE mail_broadcasts SET partial = TRUE, finished_at = now() WHERE finished_at IS NULL;
