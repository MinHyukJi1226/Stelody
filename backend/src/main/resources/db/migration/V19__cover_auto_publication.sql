CREATE TABLE app.cover_publication_control (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    enabled BOOLEAN NOT NULL DEFAULT false,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version>=0),
    edited_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO app.cover_publication_control(singleton) VALUES(true);
GRANT SELECT ON app.cover_publication_control TO "${runtimeRole}";
GRANT UPDATE (enabled,version,edited_at) ON app.cover_publication_control TO "${runtimeRole}";

CREATE TABLE app.cover_auto_registration (
    review_id UUID PRIMARY KEY REFERENCES app.review_item(id),
    song_id UUID NOT NULL UNIQUE REFERENCES app.song_entry(id),
    video_id UUID NOT NULL UNIQUE REFERENCES app.video(id),
    member_id UUID NOT NULL REFERENCES app.member(id),
    rule_version VARCHAR(120) NOT NULL,
    reason VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX cover_auto_registration_time_idx ON app.cover_auto_registration(processed_at DESC,review_id);
GRANT SELECT ON app.cover_auto_registration TO "${runtimeRole}";

-- The collector may create a fixed-shape new cover only. It cannot edit existing songs,
-- assign account roles, access private data, or overwrite administrator decisions.
CREATE FUNCTION app.publish_discovered_cover(
    candidate_id UUID, observed_at TIMESTAMPTZ, participant_id UUID,
    participant_version BIGINT, normalized_title TEXT, can_embed BOOLEAN,
    publication_rule TEXT, owner_token UUID)
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE
    candidate app.review_item%ROWTYPE;
    source_channel app.channel%ROWTYPE;
    song UUID := gen_random_uuid();
    video UUID := gen_random_uuid();
BEGIN
    PERFORM singleton FROM app.collection_control c WHERE c.singleton AND c.owner_token=publish_discovered_cover.owner_token FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'LOCK_LOST'; END IF;
    PERFORM singleton FROM app.cover_publication_control WHERE singleton AND enabled FOR SHARE;
    IF NOT FOUND THEN RETURN NULL; END IF;
    SELECT * INTO candidate FROM app.review_item WHERE id=candidate_id FOR UPDATE;
    IF NOT FOUND OR candidate.review_status<>'PENDING' OR candidate.availability<>'PUBLIC'
        OR candidate.disposition<>'REVIEW' OR candidate.suggested_type<>'COVER'
        OR candidate.decision_reason<>'EXPLICIT_SOLO_COVER_CREDIT'
        OR candidate.source_observed_at<>observed_at OR candidate.source_published_at IS NULL
        OR candidate.source_published_at>observed_at OR candidate.source_duration_seconds IS NULL
        OR candidate.source_duration_seconds<=180 OR candidate.source_title IS NULL
        OR btrim(candidate.source_title)='' OR length(candidate.source_title)>300
        OR normalized_title IS NULL OR btrim(normalized_title)='' OR length(normalized_title)>400
        OR publication_rule IS NULL OR publication_rule<>'solo-credit-v1' THEN RETURN NULL; END IF;
    SELECT * INTO source_channel FROM app.channel WHERE id=candidate.channel_id FOR SHARE;
    IF NOT FOUND OR NOT source_channel.collection_enabled OR source_channel.channel_type NOT IN ('GROUP','MEMBER') THEN RETURN NULL; END IF;
    PERFORM id FROM app.member WHERE id=participant_id AND version=participant_version FOR SHARE;
    IF NOT FOUND OR (source_channel.channel_type='MEMBER' AND source_channel.member_id IS DISTINCT FROM participant_id) THEN
        UPDATE app.review_item SET decision_reason='PARTICIPANT_CONFIGURATION_CHANGED',version=version+1 WHERE id=candidate_id;
        RETURN NULL;
    END IF;
    IF EXISTS(SELECT 1 FROM app.video WHERE youtube_id=candidate.youtube_id) THEN RETURN NULL; END IF;
    INSERT INTO app.song_entry(id,title,search_title,song_type,visibility)
        VALUES(song,candidate.source_title,normalized_title,'COVER','DRAFT');
    -- A concurrent manual registration wins the unique video ID without leaving an orphan song.
    INSERT INTO app.video(id,song_id,youtube_id,channel_id,video_kind,availability,source_title,
        source_published_at,source_thumbnail_url,source_duration_seconds,source_observed_at,status_observed_at,embeddable)
        VALUES(video,song,candidate.youtube_id,candidate.channel_id,'OFFICIAL_COVER','PUBLIC',candidate.source_title,
        candidate.source_published_at,candidate.source_thumbnail_url,candidate.source_duration_seconds,observed_at,observed_at,can_embed)
        ON CONFLICT(youtube_id) DO NOTHING;
    IF NOT FOUND THEN
        DELETE FROM app.song_entry WHERE id=song;
        RETURN NULL;
    END IF;
    INSERT INTO app.song_member(song_id,member_id,position,confirmed) VALUES(song,participant_id,0,true);
    UPDATE app.song_entry SET representative_video_id=video,visibility='PUBLISHED' WHERE id=song;
    UPDATE app.review_item SET review_status='REGISTERED',registered_video_id=video,version=version+1 WHERE id=candidate_id;
    INSERT INTO app.cover_auto_registration(review_id,song_id,video_id,member_id,rule_version,reason,processed_at)
        VALUES(candidate_id,song,video,participant_id,candidate.rule_version||'/'||publication_rule,'EXPLICIT_SOLO_COVER_CREDIT',observed_at);
    RETURN song;
END;
$$;
REVOKE ALL ON FUNCTION app.publish_discovered_cover(UUID,TIMESTAMPTZ,UUID,BIGINT,TEXT,BOOLEAN,TEXT,UUID) FROM PUBLIC;
