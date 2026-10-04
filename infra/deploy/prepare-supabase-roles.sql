-- New Supabase project only. Run as its administrator before Flyway.
-- Preserve provider schemas, roles, and global PUBLIC defaults.
\set ON_ERROR_STOP on
BEGIN;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname IN ('app','session')) THEN
        RAISE EXCEPTION 'Existing Stelody schemas detected; stop and inspect';
    END IF;
END $$;
CREATE ROLE stelody_migrator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE ROLE stelody_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE ROLE stelody_collector NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE ROLE stelody_operator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
DO $$ BEGIN
    EXECUTE format('GRANT CONNECT, CREATE ON DATABASE %I TO stelody_migrator', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO stelody_app, stelody_collector, stelody_operator', current_database());
END $$;
COMMIT;
