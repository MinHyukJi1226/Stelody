-- pg_trgm is trusted: the migration role can install it with database CREATE privileges.
-- Hosted databases may already have it in another schema; use its actual namespace.
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA app;
DO $$
DECLARE
    extension_schema TEXT;
    entry RECORD;
BEGIN
    SELECT n.nspname INTO extension_schema FROM pg_extension e
        JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname = 'pg_trgm';
    FOR entry IN SELECT * FROM (VALUES
        ('song_entry', 'search_title'), ('song_alias', 'search_alias'),
        ('musical_work', 'search_title'), ('work_alias', 'search_alias'),
        ('member', 'search_name'), ('member_alias', 'search_alias'),
        ('artist', 'search_name'), ('artist_alias', 'search_alias')
    ) AS search_columns(table_name, column_name)
    LOOP
        EXECUTE format('CREATE INDEX %I ON app.%I USING gin (%I %I.gin_trgm_ops)',
            entry.table_name || '_trgm_idx', entry.table_name, entry.column_name, extension_schema);
    END LOOP;
END $$;
