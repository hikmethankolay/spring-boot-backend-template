-- V9 made session_started_at NOT NULL without a default, so a version before 3.2.0 (still running during a
-- rolling update, or started again by a rollback) cannot store refresh tokens. With a default its inserts work.
ALTER TABLE refresh_tokens ALTER COLUMN session_started_at SET DEFAULT now();
