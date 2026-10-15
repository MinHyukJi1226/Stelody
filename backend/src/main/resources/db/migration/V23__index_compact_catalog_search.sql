-- Match the whitespace-free expressions used by public catalog search.
-- pg_trgm can be installed outside app on hosted PostgreSQL instances.
DO $$
DECLARE
    extension_schema TEXT;
    entry RECORD;
BEGIN
    SELECT n.nspname INTO extension_schema FROM pg_extension e
        JOIN pg_namespace n ON n.oid=e.extnamespace WHERE e.extname='pg_trgm';
    IF extension_schema IS NULL THEN RAISE EXCEPTION 'pg_trgm is required'; END IF;
    FOR entry IN SELECT * FROM (VALUES
        ('song_entry', 'search_title'), ('song_alias', 'search_alias'),
        ('musical_work', 'search_title'), ('work_alias', 'search_alias'),
        ('member', 'search_name'), ('member_alias', 'search_alias'),
        ('artist', 'search_name'), ('artist_alias', 'search_alias')
    ) AS search_columns(table_name, column_name)
    LOOP
        EXECUTE format('CREATE INDEX %I ON app.%I USING gin (replace(%I, '' '', '''') %I.gin_trgm_ops)',
            entry.table_name || '_compact_trgm_idx', entry.table_name, entry.column_name, extension_schema);
    END LOOP;
END $$;
CREATE INDEX song_search_compact_title_idx ON app.song_entry (replace(search_title, ' ', ''));
