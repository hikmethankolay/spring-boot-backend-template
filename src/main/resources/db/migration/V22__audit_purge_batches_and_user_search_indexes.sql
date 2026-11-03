-- Retention in bounded batches: one DELETE of a whole year of events could outlast statement_timeout and hold its
-- locks that long. AuditRetention calls it until a batch comes back short. Same rules as V12 (SECURITY DEFINER, fixed
-- search path, the last 90 days kept); the default batch size keeps purge_audit_events(older_than) callers working.
DROP FUNCTION purge_audit_events(TIMESTAMPTZ);

CREATE FUNCTION purge_audit_events(older_than TIMESTAMPTZ, batch_size INTEGER DEFAULT 10000) RETURNS INTEGER
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = ${flyway:defaultSchema}, pg_temp AS $$
DECLARE
    removed INTEGER;
BEGIN
    IF older_than > now() - INTERVAL '90 days' THEN
        RAISE EXCEPTION 'audit events younger than 90 days are kept (asked to remove those before %)', older_than;
    END IF;
    DELETE FROM audit_events WHERE id IN (
        SELECT id FROM audit_events WHERE occurred_at < older_than ORDER BY occurred_at LIMIT batch_size);
    GET DIAGNOSTICS removed = ROW_COUNT;
    RETURN removed;
END
$$;

-- The administrators' user list sorts by these.
CREATE INDEX ix_users_created_at ON users (created_at);
CREATE INDEX ix_users_updated_at ON users (updated_at);

-- Its search (q) matches anywhere in the username or email, which only a trigram index serves. pg_trgm needs a role
-- allowed to create extensions, which managed databases do not always give the schema owner: without it the search
-- keeps scanning the table, and an administrator can run CREATE EXTENSION pg_trgm and these indexes later.
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS pg_trgm;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'pg_trgm not created (%), user search stays unindexed', SQLERRM;
END
$$;

DO $$
DECLARE
    trgm_schema NAME := (SELECT n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
                         WHERE e.extname = 'pg_trgm');
BEGIN
    IF trgm_schema IS NOT NULL THEN
        EXECUTE format('CREATE INDEX ix_users_username_trgm ON users USING gin (lower(username) %I.gin_trgm_ops)',
                       trgm_schema);
        EXECUTE format('CREATE INDEX ix_users_email_trgm ON users USING gin (email %I.gin_trgm_ops)', trgm_schema);
    END IF;
END
$$;
