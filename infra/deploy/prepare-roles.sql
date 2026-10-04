-- New, empty database only. Run with the database administrator's psql connection.
-- Existing roles cause a rollback; this script never changes their passwords/privileges.
\set ON_ERROR_STOP on
BEGIN;
CREATE ROLE stelody_migrator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE ROLE stelody_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE ROLE stelody_collector NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE ROLE stelody_operator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
DO $$ BEGIN
    EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database());
    EXECUTE format('GRANT CONNECT, CREATE ON DATABASE %I TO stelody_migrator', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO stelody_app, stelody_collector, stelody_operator', current_database());
END $$;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
COMMIT;
