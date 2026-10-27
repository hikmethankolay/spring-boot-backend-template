-- HTML alternative of the body, sent with the text body as multipart/alternative; null for plain-text-only emails.

ALTER TABLE email_outbox ADD COLUMN html_body TEXT;
