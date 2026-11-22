ALTER TABLE app.playlist ADD COLUMN public_id VARCHAR(10)
    CHECK (public_id ~ '^[A-Za-z0-9]{10}$');
CREATE UNIQUE INDEX playlist_public_id_unique_idx ON app.playlist(public_id);

-- Keep issuance in the database so every insertion path uses the same collision handling.
CREATE FUNCTION app.new_playlist_public_id() RETURNS TEXT
LANGUAGE plpgsql VOLATILE SET search_path = pg_catalog AS $$
DECLARE
    alphabet CONSTANT TEXT := 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    candidate TEXT;
    random_bytes BYTEA;
    byte_position INTEGER;
    sample INTEGER;
BEGIN
    LOOP
        candidate := '';
        WHILE length(candidate) < 10 LOOP
            random_bytes := uuid_send(gen_random_uuid());
            FOR byte_position IN 0..15 LOOP
                -- UUID version and variant bytes contain fixed bits.
                CONTINUE WHEN byte_position IN (6, 8);
                sample := get_byte(random_bytes, byte_position);
                -- Reject the uneven tail instead of biasing the 62-character alphabet.
                CONTINUE WHEN sample >= 248;
                candidate := candidate || substr(alphabet, sample % 62 + 1, 1);
                EXIT WHEN length(candidate) = 10;
            END LOOP;
        END LOOP;
        -- Serialize identical candidates across users; the unique index remains the final guard.
        PERFORM pg_advisory_xact_lock(hashtextextended('playlist-public-id:' || candidate, 0));
        IF NOT EXISTS (SELECT 1 FROM app.playlist WHERE public_id = candidate) THEN
            RETURN candidate;
        END IF;
    END LOOP;
END;
$$;
REVOKE ALL ON FUNCTION app.new_playlist_public_id() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.new_playlist_public_id() TO "${runtimeRole}";

UPDATE app.playlist SET public_id = app.new_playlist_public_id();
ALTER TABLE app.playlist ALTER COLUMN public_id SET NOT NULL,
    ALTER COLUMN public_id SET DEFAULT app.new_playlist_public_id();
