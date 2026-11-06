-- The append-only trigger of V12 looked the table owner up in an unqualified pg_tables, with no fixed search path.
-- A session's temporary objects are searched before pg_catalog, so the app's role could create a temp view named
-- pg_tables that names itself as owner and then delete audit rows. This reads the owner from pg_catalog by the
-- trigger's relation id and pins the search path, so nothing a session creates can answer for it.
CREATE OR REPLACE FUNCTION audit_events_append_only() RETURNS trigger LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_user = pg_catalog.pg_get_userbyid(
            (SELECT c.relowner FROM pg_catalog.pg_class c WHERE c.oid = TG_RELID)) THEN
        RETURN NULL;
    END IF;
    RAISE EXCEPTION 'audit_events is append-only (% refused)', TG_OP USING ERRCODE = 'insufficient_privilege';
END
$$;
