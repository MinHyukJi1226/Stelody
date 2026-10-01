-- Test-only credentials. Neither application connection uses the container superuser.
CREATE ROLE stelody_migrator LOGIN PASSWORD 'test-migrator' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE ROLE stelody_app LOGIN PASSWORD 'test-runtime' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE ROLE stelody_collector LOGIN PASSWORD 'test-collector' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT, CREATE ON DATABASE %I TO stelody_migrator', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO stelody_app, stelody_collector', current_database());
END
$$;
