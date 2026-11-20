-- Only newly published covers enter the original metadata queue.
ALTER TABLE app.cover_auto_registration
    ADD COLUMN original_lookup_after TIMESTAMPTZ,
    ADD COLUMN original_lookup_attempts SMALLINT NOT NULL DEFAULT 0 CHECK (original_lookup_attempts BETWEEN 0 AND 3);
ALTER TABLE app.premiere_auto_registration
    ADD COLUMN original_lookup_after TIMESTAMPTZ,
    ADD COLUMN original_lookup_attempts SMALLINT NOT NULL DEFAULT 0 CHECK (original_lookup_attempts BETWEEN 0 AND 3);
CREATE INDEX cover_auto_original_due_idx ON app.cover_auto_registration(original_lookup_after, review_id)
    WHERE original_lookup_after IS NOT NULL;
CREATE INDEX premiere_auto_original_due_idx ON app.premiere_auto_registration(original_lookup_after, review_id)
    WHERE original_lookup_after IS NOT NULL;
GRANT UPDATE (original_lookup_after, original_lookup_attempts)
    ON app.cover_auto_registration, app.premiere_auto_registration TO "${runtimeRole}";

-- Keep existing publication checks and update only the row created by this call, atomically.
CREATE FUNCTION app.publish_collected_song(
    candidate_id UUID, observed_at TIMESTAMPTZ, participant_id UUID,
    participant_version BIGINT, display_title TEXT, normalized_title TEXT, can_embed BOOLEAN,
    publication_rule TEXT, owner_token UUID, premiere BOOLEAN)
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE
    published_song UUID;
BEGIN
    IF display_title IS NULL OR btrim(display_title)='' OR length(display_title)>300 THEN RETURN NULL; END IF;
    IF premiere THEN
        published_song := app.publish_completed_premiere(candidate_id,observed_at,participant_id,
            participant_version,normalized_title,can_embed,publication_rule,owner_token);
    ELSE
        published_song := app.publish_discovered_cover(candidate_id,observed_at,participant_id,
            participant_version,normalized_title,can_embed,publication_rule,owner_token);
    END IF;
    IF published_song IS NULL THEN RETURN NULL; END IF;
    UPDATE app.song_entry SET title=display_title WHERE id=published_song;
    IF premiere THEN
        UPDATE app.premiere_auto_registration SET original_lookup_after=observed_at
        WHERE review_id=candidate_id AND song_id=published_song
          AND EXISTS (SELECT 1 FROM app.song_entry WHERE id=published_song AND song_type='COVER');
    ELSE
        UPDATE app.cover_auto_registration SET original_lookup_after=observed_at
        WHERE review_id=candidate_id AND song_id=published_song;
    END IF;
    RETURN published_song;
END;
$$;
REVOKE ALL ON FUNCTION app.publish_collected_song(UUID,TIMESTAMPTZ,UUID,BIGINT,TEXT,TEXT,BOOLEAN,TEXT,UUID,BOOLEAN) FROM PUBLIC;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stelody_collector') THEN
        GRANT EXECUTE ON FUNCTION app.publish_collected_song(UUID,TIMESTAMPTZ,UUID,BIGINT,TEXT,TEXT,BOOLEAN,TEXT,UUID,BOOLEAN) TO stelody_collector;
    END IF;
END $$;
