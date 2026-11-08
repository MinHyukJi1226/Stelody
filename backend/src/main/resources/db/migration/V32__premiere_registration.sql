ALTER TABLE app.youtube_public_schedule DROP CONSTRAINT youtube_public_schedule_check;
ALTER TABLE app.youtube_public_schedule
    ADD COLUMN song_type VARCHAR(16) CHECK (song_type IN ('COVER','ORIGINAL')),
    ADD COLUMN actual_start_at TIMESTAMPTZ,
    ADD COLUMN actual_end_at TIMESTAMPTZ;

CREATE TABLE app.premiere_auto_registration (
    review_id UUID PRIMARY KEY REFERENCES app.review_item(id),
    song_id UUID NOT NULL UNIQUE REFERENCES app.song_entry(id),
    video_id UUID NOT NULL UNIQUE REFERENCES app.video(id),
    processed_at TIMESTAMPTZ NOT NULL,
    rule_version VARCHAR(120) NOT NULL
);
GRANT SELECT ON app.premiere_auto_registration TO "${runtimeRole}";

CREATE FUNCTION app.publish_completed_premiere(
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
    PERFORM singleton FROM app.collection_control c WHERE c.singleton AND c.owner_token=publish_completed_premiere.owner_token FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'LOCK_LOST'; END IF;
    PERFORM singleton FROM app.cover_publication_control WHERE singleton AND enabled FOR SHARE;
    IF NOT FOUND THEN RETURN NULL; END IF;
    SELECT * INTO candidate FROM app.review_item WHERE id=candidate_id FOR UPDATE;
    IF NOT FOUND OR candidate.review_status<>'PENDING' OR candidate.availability<>'PUBLIC'
        OR candidate.disposition<>'REVIEW' OR candidate.suggested_type NOT IN ('COVER','ORIGINAL')
        OR candidate.decision_reason<>'EXPLICIT_PREMIERE_CREDIT'
        OR candidate.source_observed_at<>observed_at OR candidate.source_published_at IS NULL
        OR candidate.source_published_at>observed_at OR candidate.source_duration_seconds IS NULL
        OR candidate.source_duration_seconds<=0 OR candidate.source_title IS NULL
        OR btrim(candidate.source_title)='' OR length(candidate.source_title)>300
        OR normalized_title IS NULL OR btrim(normalized_title)='' OR length(normalized_title)>400
        OR publication_rule IS NULL OR publication_rule<>'premiere-credit-v1' THEN RETURN NULL; END IF;
    PERFORM youtube_id FROM app.youtube_public_schedule p
        WHERE p.youtube_id=candidate.youtube_id AND p.channel_id=candidate.channel_id
          AND p.observed_at=publish_completed_premiere.observed_at
          AND actual_start_at IS NOT NULL AND actual_end_at IS NOT NULL
          AND actual_start_at<=actual_end_at AND actual_end_at<=publish_completed_premiere.observed_at
          AND song_type=candidate.suggested_type FOR UPDATE;
    IF NOT FOUND THEN RETURN NULL; END IF;
    SELECT * INTO source_channel FROM app.channel WHERE id=candidate.channel_id FOR SHARE;
    IF NOT FOUND OR NOT source_channel.collection_enabled OR source_channel.channel_type NOT IN ('GROUP','MEMBER') THEN RETURN NULL; END IF;
    PERFORM id FROM app.member WHERE id=participant_id AND version=participant_version FOR SHARE;
    IF NOT FOUND OR (source_channel.channel_type='MEMBER' AND source_channel.member_id IS DISTINCT FROM participant_id) THEN
        UPDATE app.review_item SET decision_reason='PARTICIPANT_CONFIGURATION_CHANGED',version=version+1 WHERE id=candidate_id;
        RETURN NULL;
    END IF;
    IF EXISTS(SELECT 1 FROM app.video WHERE youtube_id=candidate.youtube_id) THEN RETURN NULL; END IF;
    INSERT INTO app.song_entry(id,title,search_title,song_type,visibility)
        VALUES(song,candidate.source_title,normalized_title,candidate.suggested_type,'DRAFT');
    -- A concurrent manual registration wins the unique video ID without leaving an orphan song.
    INSERT INTO app.video(id,song_id,youtube_id,channel_id,video_kind,availability,source_title,
        source_published_at,source_thumbnail_url,source_duration_seconds,source_observed_at,status_observed_at,embeddable)
        VALUES(video,song,candidate.youtube_id,candidate.channel_id,CASE WHEN candidate.suggested_type='COVER' THEN 'OFFICIAL_COVER' ELSE 'OTHER' END,'PUBLIC',candidate.source_title,
        candidate.source_published_at,candidate.source_thumbnail_url,candidate.source_duration_seconds,observed_at,observed_at,can_embed)
        ON CONFLICT(youtube_id) DO NOTHING;
    IF NOT FOUND THEN
        DELETE FROM app.song_entry WHERE id=song;
        RETURN NULL;
    END IF;
    INSERT INTO app.song_member(song_id,member_id,position,confirmed) VALUES(song,participant_id,0,true);
    UPDATE app.song_entry SET representative_video_id=video,visibility='PUBLISHED' WHERE id=song;
    UPDATE app.review_item SET review_status='REGISTERED',registered_video_id=video,version=version+1 WHERE id=candidate_id;
    INSERT INTO app.premiere_auto_registration(review_id,song_id,video_id,processed_at,rule_version)
        VALUES(candidate_id,song,video,observed_at,candidate.rule_version||'/'||publication_rule);
    RETURN song;
END;
$$;
REVOKE ALL ON FUNCTION app.publish_completed_premiere(UUID,TIMESTAMPTZ,UUID,BIGINT,TEXT,BOOLEAN,TEXT,UUID) FROM PUBLIC;

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stelody_collector') THEN
        GRANT EXECUTE ON FUNCTION app.publish_completed_premiere(UUID,TIMESTAMPTZ,UUID,BIGINT,TEXT,BOOLEAN,TEXT,UUID)
            TO stelody_collector;
    END IF;
END $$;
