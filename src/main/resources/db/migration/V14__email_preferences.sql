-- Which optional email categories (MailCategory, e.g. 'product-updates') a user turned off or back on. No row means
-- the category's default, which is on. Transactional categories (account, security) are never stored: they cannot be
-- turned off.

CREATE TABLE email_preferences (
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    category   VARCHAR(40)  NOT NULL,
    enabled    BOOLEAN      NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    -- How the last change was made: user (preferences page), link (unsubscribe page), one-click (mail client).
    source     VARCHAR(20)  NOT NULL,
    PRIMARY KEY (user_id, category)
);

-- Unsubscribe token of an email in an optional category, sent in its List-Unsubscribe header; null otherwise.
ALTER TABLE email_outbox ADD COLUMN unsubscribe_token VARCHAR(200);
