-- Squashed initial schema, equivalent to the former V1 through V35 on an empty DB.
-- Existing databases must use the explicit rebaseline command; never replay this SQL there.
-- Spring Session JDBC table definitions are based on schema-postgresql.sql (Apache-2.0).
-- Flyway creates app/session and retains ownership with the migration role.
SET LOCAL check_function_bodies = false;

CREATE FUNCTION app.complete_review_candidates(candidates jsonb, selected jsonb, batch uuid, request_hash text, actor uuid) RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
DECLARE targets uuid[]; removed integer;
BEGIN
    IF NOT EXISTS(SELECT 1 FROM app.app_user WHERE id=actor AND role='ADMIN' AND status='ACTIVE') THEN
        RETURN -2;
    END IF;
    IF jsonb_typeof(candidates) IS DISTINCT FROM 'array' OR jsonb_array_length(candidates)>10000 THEN
        RAISE EXCEPTION 'INVALID_REVIEW_REQUEST';
    END IF;
    IF jsonb_typeof(selected) IS DISTINCT FROM 'array' OR jsonb_array_length(selected)=0
       OR jsonb_array_length(selected)>100 OR batch IS NULL OR request_hash IS NULL THEN
        RAISE EXCEPTION 'INVALID_REVIEW_REQUEST';
    END IF;
    IF EXISTS(SELECT 1 FROM jsonb_array_elements_text(selected) s(id)
      LEFT JOIN app.review_item r ON r.id=s.id::uuid
      WHERE r.id IS NULL OR r.review_status<>'REGISTERED' OR r.registered_batch_id IS DISTINCT FROM batch
        OR r.registered_batch_hash IS DISTINCT FROM request_hash) THEN RETURN -1; END IF;
    -- Lock the snapshot in a stable order. Already registered/deleted rows are harmless on replay.
    PERFORM r.id FROM app.review_item r
      JOIN jsonb_to_recordset(candidates) AS p(id uuid,version bigint) ON r.id=p.id
      ORDER BY r.id FOR UPDATE OF r;
    IF EXISTS(SELECT 1 FROM app.review_item r
      JOIN jsonb_to_recordset(candidates) AS p(id uuid,version bigint) ON r.id=p.id
      WHERE r.review_status<>'REGISTERED' AND
        (r.version IS DISTINCT FROM p.version OR r.review_status<>'PENDING' OR r.disposition<>'REVIEW'
         OR r.source_observed_at<=CURRENT_TIMESTAMP-INTERVAL '30 days')) THEN
        RETURN -1;
    END IF;
    SELECT array_agg(r.id) INTO targets FROM app.review_item r
      JOIN jsonb_to_recordset(candidates) AS p(id uuid,version bigint) ON r.id=p.id
      WHERE r.review_status='PENDING' AND r.disposition='REVIEW'
        AND r.registered_video_id IS NULL
        AND NOT EXISTS(SELECT 1 FROM app.video v WHERE v.youtube_id=r.youtube_id)
        AND NOT EXISTS(SELECT 1 FROM app.cover_auto_registration a WHERE a.review_id=r.id);
    DELETE FROM app.admin_audit WHERE review_id=ANY(targets);
    DELETE FROM app.review_item WHERE id=ANY(targets);
    GET DIAGNOSTICS removed=ROW_COUNT;
    RETURN removed;
END;
$$;

CREATE FUNCTION app.new_playlist_public_id() RETURNS text
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog'
    AS $$
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

CREATE FUNCTION app.publish_collected_song(candidate_id uuid, observed_at timestamp with time zone, participant_id uuid, participant_version bigint, display_title text, normalized_title text, can_embed boolean, publication_rule text, owner_token uuid, premiere boolean) RETURNS uuid
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
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

CREATE FUNCTION app.publish_completed_premiere(candidate_id uuid, observed_at timestamp with time zone, participant_id uuid, participant_version bigint, normalized_title text, can_embed boolean, publication_rule text, owner_token uuid) RETURNS uuid
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
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

CREATE FUNCTION app.publish_discovered_cover(candidate_id uuid, observed_at timestamp with time zone, participant_id uuid, participant_version bigint, normalized_title text, can_embed boolean, publication_rule text, owner_token uuid) RETURNS uuid
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
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

CREATE FUNCTION app.revoke_persistent_login_on_account_change() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
BEGIN
    DELETE FROM app.persistent_login WHERE user_id=NEW.id;
    RETURN NEW;
END;
$$;

CREATE FUNCTION app.withdraw_account(account_id uuid, current_session_hash text) RETURNS boolean
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
BEGIN
    IF NOT pg_try_advisory_xact_lock(2026100301::bigint) THEN
        RETURN FALSE;
    END IF;
    PERFORM id FROM app.app_user WHERE id=account_id AND status='ACTIVE' FOR UPDATE;
    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;
    PERFORM user_id FROM app.account_reauthentication
        WHERE user_id=account_id AND session_hash=current_session_hash
          AND status='CONFIRMED' AND expires_at>clock_timestamp() FOR UPDATE;
    IF NOT FOUND OR EXISTS (
        SELECT 1 FROM app.youtube_connection WHERE user_id=account_id
          AND (status<>'DISCONNECTED' OR access_token IS NOT NULL OR refresh_token IS NOT NULL)
    ) THEN
        RETURN FALSE;
    END IF;
    DELETE FROM session.spring_session WHERE principal_name=account_id::text;
    UPDATE app.catalog_audit SET target_id=gen_random_uuid()
        WHERE target_type='ACCOUNT_ROLE' AND target_id=account_id;
    -- ON DELETE SET NULL anonymizes actor IDs; CASCADE removes all private lists and tokens.
    DELETE FROM app.app_user WHERE id=account_id;
    RETURN TRUE;
END;
$$;

CREATE TABLE app.account_reauthentication (
    user_id uuid NOT NULL,
    session_hash character varying(64) NOT NULL,
    generation uuid NOT NULL,
    status character varying(16) NOT NULL,
    state_hash character varying(64),
    expires_at timestamp with time zone NOT NULL,
    CONSTRAINT account_reauthentication_check CHECK ((((status)::text = 'PENDING'::text) = (state_hash IS NOT NULL))),
    CONSTRAINT account_reauthentication_status_check CHECK ((status IN ('PENDING', 'VERIFYING', 'CONFIRMED')))
);

CREATE TABLE app.admin_audit (
    id uuid NOT NULL,
    review_id uuid NOT NULL,
    actor_id uuid,
    before_status character varying(16) NOT NULL,
    after_status character varying(16) NOT NULL,
    before_note character varying(500),
    after_note character varying(500) NOT NULL,
    changed_at timestamp with time zone NOT NULL
);

CREATE TABLE app.app_user (
    id uuid NOT NULL,
    google_subject character varying(255) NOT NULL,
    email character varying(320) NOT NULL,
    role character varying(16) DEFAULT 'USER'::character varying NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_login_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT app_user_role_check CHECK ((role IN ('USER', 'ADMIN'))),
    CONSTRAINT app_user_status_check CHECK ((status IN ('ACTIVE', 'SUSPENDED')))
);

CREATE TABLE app.artist (
    id uuid NOT NULL,
    name character varying(200) NOT NULL,
    search_name character varying(300) NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    edited_at timestamp with time zone,
    CONSTRAINT artist_name_check CHECK ((btrim((name)::text) <> ''::text))
);

CREATE TABLE app.artist_alias (
    artist_id uuid NOT NULL,
    alias character varying(200) NOT NULL,
    search_alias character varying(300) NOT NULL
);

CREATE TABLE app.artist_registration_request (
    id uuid NOT NULL,
    actor_id uuid,
    body_hash character varying(64) NOT NULL,
    status character varying(16) DEFAULT 'PROCESSING'::character varying NOT NULL,
    artist_id uuid,
    error_code character varying(64),
    error_status smallint,
    created_at timestamp with time zone DEFAULT statement_timestamp() NOT NULL,
    expires_at timestamp with time zone DEFAULT (statement_timestamp() + '24:00:00'::interval) NOT NULL,
    finished_at timestamp with time zone,
    CONSTRAINT artist_registration_request_body_hash_check CHECK (((body_hash)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT artist_registration_request_check CHECK ((expires_at > created_at)),
    CONSTRAINT artist_registration_request_check1 CHECK (((((status)::text = 'PROCESSING'::text) AND (artist_id IS NULL) AND (error_code IS NULL) AND (error_status IS NULL) AND (finished_at IS NULL)) OR (((status)::text = 'COMPLETED'::text) AND (artist_id IS NOT NULL) AND (error_code IS NULL) AND (error_status IS NULL) AND (finished_at IS NOT NULL)) OR (((status)::text = 'FAILED'::text) AND (artist_id IS NULL) AND (error_code IS NOT NULL) AND ((error_status >= 400) AND (error_status <= 599)) AND (finished_at IS NOT NULL)) OR (((status)::text = 'EXPIRED'::text) AND (artist_id IS NULL) AND (error_code IS NULL) AND (error_status IS NULL) AND (finished_at IS NULL)))),
    CONSTRAINT artist_registration_request_status_check CHECK ((status IN ('PROCESSING', 'COMPLETED', 'FAILED', 'EXPIRED')))
);

CREATE TABLE app.catalog_audit (
    id uuid NOT NULL,
    target_type character varying(24) NOT NULL,
    target_id uuid NOT NULL,
    actor_id uuid,
    database_actor text DEFAULT CURRENT_USER NOT NULL,
    action character varying(24) NOT NULL,
    before_value jsonb,
    after_value jsonb NOT NULL,
    reason character varying(500) NOT NULL,
    changed_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT catalog_audit_reason_check CHECK ((btrim((reason)::text) <> ''::text))
);

CREATE TABLE app.catalog_state (
    singleton boolean DEFAULT true NOT NULL,
    view_publication_id uuid,
    CONSTRAINT catalog_state_singleton_check CHECK (singleton)
);

CREATE TABLE app.channel (
    id uuid NOT NULL,
    youtube_id character varying(24) NOT NULL,
    name character varying(200) NOT NULL,
    member_id uuid,
    channel_type character varying(16) NOT NULL,
    collection_enabled boolean DEFAULT false NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    edited_at timestamp with time zone,
    CONSTRAINT channel_channel_type_check CHECK ((channel_type IN ('GROUP', 'MEMBER', 'EXTERNAL'))),
    CONSTRAINT channel_youtube_id_check CHECK (((youtube_id)::text ~ '^UC[A-Za-z0-9_-]{22}$'::text))
);

CREATE TABLE app.collection_control (
    singleton boolean DEFAULT true NOT NULL,
    owner_token uuid,
    CONSTRAINT collection_control_singleton_check CHECK (singleton)
);

CREATE TABLE app.collection_retry_request (
    id uuid NOT NULL,
    kind character varying(16) NOT NULL,
    run_id uuid NOT NULL,
    expected_attempt integer NOT NULL,
    status character varying(16) DEFAULT 'QUEUED'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    execution_run_id uuid,
    lease_token uuid,
    error_code character varying(64),
    CONSTRAINT collection_retry_request_expected_attempt_check CHECK ((expected_attempt > 0)),
    CONSTRAINT collection_retry_request_kind_check CHECK ((kind IN ('VIDEO', 'DISCOVERY'))),
    CONSTRAINT collection_retry_request_status_check CHECK ((status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')))
);

CREATE TABLE app.collection_rule (
    id uuid NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    configuration jsonb NOT NULL,
    edited_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT collection_rule_configuration_check CHECK ((jsonb_typeof(configuration) = 'object'::text)),
    CONSTRAINT collection_rule_id_check CHECK ((id = '00000000-0000-0000-0000-000000000001'::uuid)),
    CONSTRAINT collection_rule_version_check CHECK ((version >= 0))
);

CREATE TABLE app.collection_run (
    id uuid NOT NULL,
    logical_slot timestamp with time zone NOT NULL,
    status character varying(24) NOT NULL,
    attempt integer NOT NULL,
    started_at timestamp with time zone NOT NULL,
    finished_at timestamp with time zone,
    error_code character varying(64),
    publication_id uuid,
    CONSTRAINT collection_run_attempt_check CHECK ((attempt > 0)),
    CONSTRAINT collection_run_logical_slot_check CHECK ((logical_slot = (date_trunc('hour'::text, (logical_slot AT TIME ZONE 'UTC'::text)) AT TIME ZONE 'UTC'::text))),
    CONSTRAINT collection_run_status_check CHECK ((status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'QUOTA_EXHAUSTED', 'TIMED_OUT')))
);

CREATE TABLE app.collection_target (
    run_id uuid NOT NULL,
    video_id uuid NOT NULL,
    youtube_id character varying(11) NOT NULL,
    channel_youtube_id character varying(24) NOT NULL,
    outcome character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    observed_at timestamp with time zone,
    view_count bigint,
    CONSTRAINT collection_target_outcome_check CHECK ((outcome IN ('PENDING', 'OBSERVED', 'SKIPPED'))),
    CONSTRAINT collection_target_view_count_check CHECK ((view_count >= 0))
);

CREATE TABLE app.cover_auto_registration (
    review_id uuid NOT NULL,
    song_id uuid NOT NULL,
    video_id uuid NOT NULL,
    member_id uuid NOT NULL,
    rule_version character varying(120) NOT NULL,
    reason character varying(64) NOT NULL,
    processed_at timestamp with time zone NOT NULL,
    original_lookup_after timestamp with time zone,
    original_lookup_attempts smallint DEFAULT 0 NOT NULL,
    CONSTRAINT cover_auto_registration_original_lookup_attempts_check CHECK (((original_lookup_attempts >= 0) AND (original_lookup_attempts <= 3)))
);

CREATE TABLE app.cover_publication_control (
    singleton boolean DEFAULT true NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    edited_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT cover_publication_control_singleton_check CHECK (singleton),
    CONSTRAINT cover_publication_control_version_check CHECK ((version >= 0))
);

CREATE TABLE app.daily_video_view (
    video_id uuid NOT NULL,
    day date NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    view_count bigint NOT NULL,
    source character varying(32) DEFAULT 'YOUTUBE_DATA_API_V3'::character varying NOT NULL,
    CONSTRAINT daily_video_view_view_count_check CHECK ((view_count >= 0))
);

CREATE TABLE app.discovery_channel_state (
    channel_id uuid NOT NULL,
    uploads_id character varying(150) NOT NULL,
    head_video_id character varying(11),
    pending_head_id character varying(11),
    page_token character varying(2048),
    in_progress boolean DEFAULT false NOT NULL,
    backfill_token character varying(2048),
    backfill_complete boolean DEFAULT false NOT NULL,
    last_scanned_at timestamp with time zone
);

CREATE TABLE app.discovery_run (
    id uuid NOT NULL,
    mode character varying(16) NOT NULL,
    channel_id uuid,
    started_at timestamp with time zone NOT NULL,
    finished_at timestamp with time zone,
    status character varying(24) NOT NULL,
    pages integer DEFAULT 0 NOT NULL,
    candidates integer DEFAULT 0 NOT NULL,
    error_code character varying(64),
    rule_version character varying(60),
    CONSTRAINT discovery_run_candidates_check CHECK ((candidates >= 0)),
    CONSTRAINT discovery_run_mode_check CHECK ((mode IN ('NEW', 'BACKFILL'))),
    CONSTRAINT discovery_run_pages_check CHECK ((pages >= 0)),
    CONSTRAINT discovery_run_status_check CHECK ((status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'QUOTA_EXHAUSTED', 'TIMED_OUT')))
);

CREATE TABLE app.external_link (
    id uuid NOT NULL,
    song_id uuid,
    work_id uuid,
    platform character varying(60) NOT NULL,
    url text NOT NULL,
    source_url text NOT NULL,
    checked_at timestamp with time zone NOT NULL,
    CONSTRAINT external_link_check CHECK (((song_id IS NULL) <> (work_id IS NULL))),
    CONSTRAINT external_link_platform_check CHECK ((btrim((platform)::text) <> ''::text))
);

CREATE TABLE app.favorite (
    user_id uuid NOT NULL,
    song_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE app.karaoke_entry (
    id uuid NOT NULL,
    song_id uuid,
    work_id uuid,
    provider character varying(2) NOT NULL,
    status character varying(16) NOT NULL,
    number character varying(40),
    source_url text,
    checked_at timestamp with time zone,
    CONSTRAINT karaoke_entry_check CHECK (((song_id IS NULL) <> (work_id IS NULL))),
    CONSTRAINT karaoke_entry_check1 CHECK (((((status)::text = 'REGISTERED'::text) AND (number IS NOT NULL) AND (btrim((number)::text) <> ''::text) AND (source_url IS NOT NULL) AND (checked_at IS NOT NULL)) OR (((status)::text <> 'REGISTERED'::text) AND (number IS NULL)))),
    CONSTRAINT karaoke_entry_provider_check CHECK ((provider IN ('TJ', 'KY'))),
    CONSTRAINT karaoke_entry_status_check CHECK ((status IN ('UNKNOWN', 'NOT_LISTED', 'REGISTERED')))
);

CREATE TABLE app.member (
    id uuid NOT NULL,
    name character varying(100) NOT NULL,
    search_name character varying(200) NOT NULL,
    generation smallint,
    activity_status character varying(16) NOT NULL,
    profile_image_url text,
    debut_date date,
    version bigint DEFAULT 0 NOT NULL,
    edited_at timestamp with time zone,
    birthday_month smallint,
    birthday_day smallint,
    unit_name character varying(100),
    chzzk_url text,
    x_url text,
    display_order integer,
    slug character varying(40),
    CONSTRAINT member_activity_status_check CHECK ((activity_status IN ('ACTIVE', 'GRADUATED'))),
    CONSTRAINT member_birthday_check CHECK ((((birthday_month IS NULL) AND (birthday_day IS NULL)) OR ((birthday_month IS NOT NULL) AND (birthday_day IS NOT NULL) AND ((birthday_month >= 1) AND (birthday_month <= 12)) AND ((birthday_day >= 1) AND (birthday_day <=
CASE birthday_month
    WHEN 2 THEN 29
    WHEN 4 THEN 30
    WHEN 6 THEN 30
    WHEN 9 THEN 30
    WHEN 11 THEN 30
    ELSE 31
END))))),
    CONSTRAINT member_chzzk_url_check CHECK (((chzzk_url IS NULL) OR (btrim(chzzk_url) <> ''::text))),
    CONSTRAINT member_display_order_check CHECK (((display_order > 0) AND (display_order < 2147483647))),
    CONSTRAINT member_generation_check CHECK ((generation > 0)),
    CONSTRAINT member_name_check CHECK ((btrim((name)::text) <> ''::text)),
    CONSTRAINT member_slug_format CHECK (((slug)::text ~ '^[a-z][a-z0-9-]{0,39}$'::text)),
    CONSTRAINT member_unit_name_check CHECK (((unit_name IS NULL) OR (btrim((unit_name)::text) <> ''::text))),
    CONSTRAINT member_x_url_check CHECK (((x_url IS NULL) OR (btrim(x_url) <> ''::text)))
);

CREATE TABLE app.member_alias (
    member_id uuid NOT NULL,
    alias character varying(200) NOT NULL,
    search_alias character varying(300) NOT NULL
);

CREATE TABLE app.musical_work (
    id uuid NOT NULL,
    title character varying(300) NOT NULL,
    search_title character varying(400) NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    edited_at timestamp with time zone,
    CONSTRAINT musical_work_title_check CHECK ((btrim((title)::text) <> ''::text))
);

CREATE TABLE app.persistent_login (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    token_hash character varying(64) NOT NULL,
    previous_token_hash character varying(64),
    previous_valid_until timestamp with time zone,
    csrf_token character varying(128) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    CONSTRAINT persistent_login_check CHECK (((expires_at > created_at) AND (expires_at <= (created_at + '30 days'::interval)))),
    CONSTRAINT persistent_login_check1 CHECK (((previous_token_hash IS NULL) = (previous_valid_until IS NULL))),
    CONSTRAINT persistent_login_token_hash_check CHECK (((token_hash)::text ~ '^[0-9a-f]{64}$'::text))
);

CREATE TABLE app.playlist (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    name character varying(50) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    public_id character varying(10) DEFAULT app.new_playlist_public_id() NOT NULL,
    CONSTRAINT playlist_name_check CHECK ((btrim((name)::text) <> ''::text)),
    CONSTRAINT playlist_public_id_check CHECK (((public_id)::text ~ '^[A-Za-z0-9]{10}$'::text)),
    CONSTRAINT playlist_version_check CHECK ((version >= 0))
);

CREATE TABLE app.playlist_item (
    id uuid NOT NULL,
    playlist_id uuid NOT NULL,
    song_id uuid NOT NULL,
    "position" integer NOT NULL,
    added_at timestamp with time zone NOT NULL,
    CONSTRAINT playlist_item_position_check CHECK (("position" >= 0))
);

CREATE TABLE app.premiere_auto_registration (
    review_id uuid NOT NULL,
    song_id uuid NOT NULL,
    video_id uuid NOT NULL,
    processed_at timestamp with time zone NOT NULL,
    rule_version character varying(120) NOT NULL,
    original_lookup_after timestamp with time zone,
    original_lookup_attempts smallint DEFAULT 0 NOT NULL,
    CONSTRAINT premiere_auto_registration_original_lookup_attempts_check CHECK (((original_lookup_attempts >= 0) AND (original_lookup_attempts <= 3)))
);

CREATE TABLE app.published_video_view (
    publication_id uuid NOT NULL,
    video_id uuid NOT NULL,
    view_count bigint NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    CONSTRAINT published_video_view_view_count_check CHECK ((view_count >= 0))
);

CREATE TABLE app.review_item (
    id uuid NOT NULL,
    youtube_id character varying(11) NOT NULL,
    channel_id uuid NOT NULL,
    source_title character varying(500),
    source_published_at timestamp with time zone,
    source_thumbnail_url text,
    source_duration_seconds bigint,
    source_observed_at timestamp with time zone NOT NULL,
    availability character varying(16) NOT NULL,
    disposition character varying(16) NOT NULL,
    suggested_type character varying(16) NOT NULL,
    rule_version character varying(60) NOT NULL,
    decision_reason character varying(64) NOT NULL,
    review_status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    review_note character varying(500),
    reviewed_at timestamp with time zone,
    first_seen_at timestamp with time zone NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    registered_video_id uuid,
    registered_batch_id uuid,
    registered_batch_hash character varying(64),
    CONSTRAINT review_batch_receipt_check CHECK ((((registered_batch_id IS NULL) AND (registered_batch_hash IS NULL)) OR ((registered_batch_id IS NOT NULL) AND (registered_batch_hash IS NOT NULL) AND ((registered_batch_hash)::text ~ '^[0-9a-f]{64}$'::text) AND ((review_status)::text = 'REGISTERED'::text)))),
    CONSTRAINT review_item_availability_check CHECK ((availability IN ('PUBLIC', 'UNLISTED', 'PRIVATE', 'DELETED', 'UNAVAILABLE'))),
    CONSTRAINT review_item_disposition_check CHECK ((disposition IN ('REVIEW', 'EXCLUDED', 'DEFERRED'))),
    CONSTRAINT review_item_review_status_check CHECK ((review_status IN ('PENDING', 'IGNORED', 'REGISTERED'))),
    CONSTRAINT review_item_source_duration_seconds_check CHECK ((source_duration_seconds >= 0)),
    CONSTRAINT review_item_suggested_type_check CHECK ((suggested_type IN ('UNKNOWN', 'COVER', 'ORIGINAL'))),
    CONSTRAINT review_item_version_check CHECK ((version >= 0)),
    CONSTRAINT review_item_youtube_id_check CHECK (((youtube_id)::text ~ '^[A-Za-z0-9_-]{11}$'::text)),
    CONSTRAINT review_registration_check CHECK ((((review_status)::text = 'REGISTERED'::text) = (registered_video_id IS NOT NULL)))
);

CREATE TABLE app.song_alias (
    song_id uuid NOT NULL,
    alias character varying(300) NOT NULL,
    search_alias character varying(400) NOT NULL
);

CREATE TABLE app.song_entry (
    id uuid NOT NULL,
    title character varying(300) NOT NULL,
    search_title character varying(400) NOT NULL,
    song_type character varying(16) NOT NULL,
    work_id uuid,
    visibility character varying(16) DEFAULT 'DRAFT'::character varying NOT NULL,
    representative_video_id uuid,
    is_special_event boolean DEFAULT false NOT NULL,
    special_event_label character varying(60),
    search_visibility character varying(16) DEFAULT 'UNCHECKED'::character varying NOT NULL,
    recommended_search_query character varying(300),
    search_checked_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    edited_at timestamp with time zone,
    CONSTRAINT song_entry_check CHECK (((is_special_event AND (special_event_label IS NOT NULL) AND (btrim((special_event_label)::text) <> ''::text)) OR ((NOT is_special_event) AND (special_event_label IS NULL)))),
    CONSTRAINT song_entry_search_visibility_check CHECK ((search_visibility IN ('UNCHECKED', 'NORMAL', 'DIFFICULT'))),
    CONSTRAINT song_entry_song_type_check CHECK ((song_type IN ('ORIGINAL', 'COVER'))),
    CONSTRAINT song_entry_title_check CHECK ((btrim((title)::text) <> ''::text)),
    CONSTRAINT song_entry_visibility_check CHECK ((visibility IN ('DRAFT', 'PUBLISHED', 'HIDDEN')))
);

CREATE TABLE app.song_external_artist (
    song_id uuid NOT NULL,
    artist_id uuid NOT NULL,
    "position" integer NOT NULL,
    confirmed boolean DEFAULT true NOT NULL,
    CONSTRAINT song_external_artist_position_check CHECK (("position" >= 0))
);

CREATE TABLE app.song_member (
    song_id uuid NOT NULL,
    member_id uuid NOT NULL,
    "position" integer NOT NULL,
    confirmed boolean DEFAULT true NOT NULL,
    CONSTRAINT song_member_position_check CHECK (("position" >= 0))
);

CREATE TABLE app.special_event_review (
    id uuid NOT NULL,
    song_id uuid NOT NULL,
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    evidence jsonb NOT NULL,
    active boolean DEFAULT true NOT NULL,
    source_expires_at timestamp with time zone NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT special_event_review_evidence_check CHECK ((jsonb_typeof(evidence) = 'array'::text)),
    CONSTRAINT special_event_review_status_check CHECK ((status IN ('PENDING', 'CONFIRMED', 'DISMISSED'))),
    CONSTRAINT special_event_review_version_check CHECK ((version >= 0))
);

CREATE TABLE app.special_event_scan_state (
    singleton boolean DEFAULT true NOT NULL,
    last_song_id uuid,
    CONSTRAINT special_event_scan_state_singleton_check CHECK (singleton)
);

CREATE TABLE app.video (
    id uuid NOT NULL,
    song_id uuid NOT NULL,
    youtube_id character varying(11) NOT NULL,
    channel_id uuid,
    video_kind character varying(24) NOT NULL,
    availability character varying(16) NOT NULL,
    source_title character varying(500) NOT NULL,
    published_at timestamp with time zone,
    thumbnail_url text,
    embeddable boolean DEFAULT true NOT NULL,
    source_published_at timestamp with time zone,
    source_thumbnail_url text,
    source_duration_seconds bigint,
    source_observed_at timestamp with time zone,
    status_observed_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    edited_at timestamp with time zone,
    view_collection_started_at timestamp with time zone,
    CONSTRAINT video_availability_check CHECK ((availability IN ('PUBLIC', 'PRIVATE', 'DELETED', 'UNAVAILABLE', 'UNLISTED'))),
    CONSTRAINT video_source_duration_seconds_check CHECK ((source_duration_seconds >= 0)),
    CONSTRAINT video_video_kind_check CHECK ((video_kind IN ('OFFICIAL_MV', 'OFFICIAL_COVER', 'AUDIO', 'REUPLOAD', 'OTHER'))),
    CONSTRAINT video_youtube_id_check CHECK (((youtube_id)::text ~ '^[A-Za-z0-9_-]{11}$'::text))
);

CREATE TABLE app.view_milestone_control (
    singleton boolean DEFAULT true NOT NULL,
    retry_after timestamp with time zone,
    last_error_code character varying(64),
    CONSTRAINT view_milestone_control_singleton_check CHECK (singleton)
);

CREATE TABLE app.view_milestone_event (
    video_id uuid NOT NULL,
    target bigint NOT NULL,
    detected_at timestamp with time zone NOT NULL,
    CONSTRAINT view_milestone_event_target_check CHECK (((target >= 100000) AND ((target % (100000)::bigint) = 0)))
);

CREATE TABLE app.view_milestone_state (
    video_id uuid NOT NULL,
    view_count bigint NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    highest_target bigint NOT NULL,
    available boolean DEFAULT true NOT NULL,
    estimated_at timestamp with time zone,
    flat_checks smallint DEFAULT 0 NOT NULL,
    next_check_at timestamp with time zone NOT NULL,
    CONSTRAINT view_milestone_state_flat_checks_check CHECK (((flat_checks >= 0) AND (flat_checks <= 2))),
    CONSTRAINT view_milestone_state_highest_target_check CHECK (((highest_target >= 0) AND ((highest_target % (100000)::bigint) = 0))),
    CONSTRAINT view_milestone_state_view_count_check CHECK ((view_count >= 0))
);

CREATE TABLE app.view_publication (
    id uuid NOT NULL,
    published_at timestamp with time zone NOT NULL
);

CREATE TABLE app.view_snapshot (
    video_id uuid NOT NULL,
    logical_slot timestamp with time zone NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    view_count bigint NOT NULL,
    source character varying(32) DEFAULT 'YOUTUBE_DATA_API_V3'::character varying NOT NULL,
    CONSTRAINT view_snapshot_view_count_check CHECK ((view_count >= 0))
);

CREATE TABLE app.withdrawal_result (
    id uuid NOT NULL,
    user_id uuid,
    token_hash character varying(64) NOT NULL,
    status character varying(24) NOT NULL,
    error_code character varying(64),
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    expires_at timestamp with time zone DEFAULT (clock_timestamp() + '24:00:00'::interval) NOT NULL,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    CONSTRAINT withdrawal_result_check CHECK ((((status)::text = 'COMPLETED'::text) = (completed_at IS NOT NULL))),
    CONSTRAINT withdrawal_result_status_check CHECK ((status IN ('PREPARED', 'PROCESSING', 'COMPLETED', 'NOT_COMPLETED', 'UNKNOWN')))
);

CREATE TABLE app.work_alias (
    work_id uuid NOT NULL,
    alias character varying(300) NOT NULL,
    search_alias character varying(400) NOT NULL
);

CREATE TABLE app.work_artist (
    work_id uuid NOT NULL,
    artist_id uuid NOT NULL,
    "position" integer NOT NULL,
    CONSTRAINT work_artist_position_check CHECK (("position" >= 0))
);

CREATE TABLE app.youtube_authorization (
    user_id uuid NOT NULL,
    generation uuid NOT NULL,
    state_hash character varying(64) NOT NULL,
    session_hash character varying(64) NOT NULL,
    verifier text NOT NULL,
    nonce character varying(128) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    return_path text,
    callback_client character varying(8) DEFAULT 'default'::character varying NOT NULL,
    callback_uri text,
    CONSTRAINT youtube_authorization_callback_client_check CHECK ((callback_client IN ('default', 'local'))),
    CONSTRAINT youtube_authorization_return_path_check CHECK ((return_path IS NULL OR (length(return_path) BETWEEN 1 AND 2048 AND left(return_path,1)='/')))
);

CREATE TABLE app.youtube_connection (
    user_id uuid NOT NULL,
    generation uuid NOT NULL,
    status character varying(30) NOT NULL,
    access_token text,
    refresh_token text,
    expires_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT youtube_connection_check CHECK ((((status)::text <> 'CONNECTED'::text) OR ((access_token IS NOT NULL) AND (refresh_token IS NOT NULL) AND (expires_at IS NOT NULL)))),
    CONSTRAINT youtube_connection_status_check CHECK ((status IN ('DISCONNECTED', 'CONNECTED', 'RECONNECT_REQUIRED', 'REVOKING')))
);

CREATE TABLE app.youtube_export (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    request_id uuid NOT NULL,
    source_playlist_id uuid NOT NULL,
    source_version bigint NOT NULL,
    name character varying(50) NOT NULL,
    status character varying(20) NOT NULL,
    youtube_playlist_id character varying(100),
    in_flight boolean DEFAULT false NOT NULL,
    in_flight_position integer,
    error_code character varying(60),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT youtube_export_in_flight_position_check CHECK ((in_flight_position >= 0)),
    CONSTRAINT youtube_export_source_version_check CHECK ((source_version >= 0)),
    CONSTRAINT youtube_export_status_check CHECK ((status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNCERTAIN', 'CANCELLED')))
);

CREATE TABLE app.youtube_export_item (
    export_id uuid NOT NULL,
    "position" integer NOT NULL,
    song_id uuid NOT NULL,
    youtube_id character varying(11),
    status character varying(10) NOT NULL,
    CONSTRAINT youtube_export_item_check CHECK ((((status)::text = 'SKIPPED'::text) OR (youtube_id IS NOT NULL))),
    CONSTRAINT youtube_export_item_position_check CHECK (("position" >= 0)),
    CONSTRAINT youtube_export_item_status_check CHECK ((status IN ('PENDING', 'COPIED', 'SKIPPED')))
);

CREATE TABLE app.youtube_public_schedule (
    youtube_id character varying(11) NOT NULL,
    channel_id uuid NOT NULL,
    title character varying(500) NOT NULL,
    scheduled_start_at timestamp with time zone NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    thumbnail_url text,
    song_type character varying(16),
    actual_start_at timestamp with time zone,
    actual_end_at timestamp with time zone,
    CONSTRAINT youtube_public_schedule_song_type_check CHECK ((song_type IN ('COVER', 'ORIGINAL'))),
    CONSTRAINT youtube_public_schedule_title_check CHECK ((btrim((title)::text) <> ''::text)),
    CONSTRAINT youtube_public_schedule_youtube_id_check CHECK (((youtube_id)::text ~ '^[A-Za-z0-9_-]{11}$'::text))
);

CREATE TABLE session.spring_session (
    primary_id character(36) NOT NULL,
    session_id character(36) NOT NULL,
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name character varying(100)
);

CREATE TABLE session.spring_session_attributes (
    session_primary_id character(36) NOT NULL,
    attribute_name character varying(200) NOT NULL,
    attribute_bytes bytea NOT NULL
);

ALTER TABLE ONLY app.account_reauthentication
    ADD CONSTRAINT account_reauthentication_pkey PRIMARY KEY (user_id, session_hash);

ALTER TABLE ONLY app.account_reauthentication
    ADD CONSTRAINT account_reauthentication_state_hash_key UNIQUE (state_hash);

ALTER TABLE ONLY app.admin_audit
    ADD CONSTRAINT admin_audit_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.app_user
    ADD CONSTRAINT app_user_google_subject_key UNIQUE (google_subject);

ALTER TABLE ONLY app.app_user
    ADD CONSTRAINT app_user_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.artist_alias
    ADD CONSTRAINT artist_alias_pkey PRIMARY KEY (artist_id, search_alias);

ALTER TABLE ONLY app.artist
    ADD CONSTRAINT artist_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.artist_registration_request
    ADD CONSTRAINT artist_registration_request_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.catalog_audit
    ADD CONSTRAINT catalog_audit_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.catalog_state
    ADD CONSTRAINT catalog_state_pkey PRIMARY KEY (singleton);

ALTER TABLE ONLY app.channel
    ADD CONSTRAINT channel_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.channel
    ADD CONSTRAINT channel_youtube_id_key UNIQUE (youtube_id);

ALTER TABLE ONLY app.collection_control
    ADD CONSTRAINT collection_control_pkey PRIMARY KEY (singleton);

ALTER TABLE ONLY app.collection_retry_request
    ADD CONSTRAINT collection_retry_request_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.collection_rule
    ADD CONSTRAINT collection_rule_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.collection_run
    ADD CONSTRAINT collection_run_logical_slot_key UNIQUE (logical_slot);

ALTER TABLE ONLY app.collection_run
    ADD CONSTRAINT collection_run_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.collection_target
    ADD CONSTRAINT collection_target_pkey PRIMARY KEY (run_id, video_id);

ALTER TABLE ONLY app.cover_auto_registration
    ADD CONSTRAINT cover_auto_registration_pkey PRIMARY KEY (review_id);

ALTER TABLE ONLY app.cover_auto_registration
    ADD CONSTRAINT cover_auto_registration_song_id_key UNIQUE (song_id);

ALTER TABLE ONLY app.cover_auto_registration
    ADD CONSTRAINT cover_auto_registration_video_id_key UNIQUE (video_id);

ALTER TABLE ONLY app.cover_publication_control
    ADD CONSTRAINT cover_publication_control_pkey PRIMARY KEY (singleton);

ALTER TABLE ONLY app.daily_video_view
    ADD CONSTRAINT daily_video_view_pkey PRIMARY KEY (video_id, day);

ALTER TABLE ONLY app.discovery_channel_state
    ADD CONSTRAINT discovery_channel_state_pkey PRIMARY KEY (channel_id);

ALTER TABLE ONLY app.discovery_run
    ADD CONSTRAINT discovery_run_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.external_link
    ADD CONSTRAINT external_link_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.favorite
    ADD CONSTRAINT favorite_pkey PRIMARY KEY (user_id, song_id);

ALTER TABLE ONLY app.karaoke_entry
    ADD CONSTRAINT karaoke_entry_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.karaoke_entry
    ADD CONSTRAINT karaoke_entry_song_id_provider_key UNIQUE (song_id, provider);

ALTER TABLE ONLY app.karaoke_entry
    ADD CONSTRAINT karaoke_entry_work_id_provider_key UNIQUE (work_id, provider);

ALTER TABLE ONLY app.member_alias
    ADD CONSTRAINT member_alias_pkey PRIMARY KEY (member_id, search_alias);

ALTER TABLE ONLY app.member
    ADD CONSTRAINT member_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.musical_work
    ADD CONSTRAINT musical_work_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.persistent_login
    ADD CONSTRAINT persistent_login_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.playlist_item
    ADD CONSTRAINT playlist_item_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.playlist_item
    ADD CONSTRAINT playlist_item_playlist_id_song_id_key UNIQUE (playlist_id, song_id);

ALTER TABLE ONLY app.playlist_item
    ADD CONSTRAINT playlist_item_position_unique UNIQUE (playlist_id, "position") DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY app.playlist
    ADD CONSTRAINT playlist_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.premiere_auto_registration
    ADD CONSTRAINT premiere_auto_registration_pkey PRIMARY KEY (review_id);

ALTER TABLE ONLY app.premiere_auto_registration
    ADD CONSTRAINT premiere_auto_registration_song_id_key UNIQUE (song_id);

ALTER TABLE ONLY app.premiere_auto_registration
    ADD CONSTRAINT premiere_auto_registration_video_id_key UNIQUE (video_id);

ALTER TABLE ONLY app.published_video_view
    ADD CONSTRAINT published_video_view_pkey PRIMARY KEY (publication_id, video_id);

ALTER TABLE ONLY app.review_item
    ADD CONSTRAINT review_item_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.review_item
    ADD CONSTRAINT review_item_youtube_id_key UNIQUE (youtube_id);

ALTER TABLE ONLY app.song_alias
    ADD CONSTRAINT song_alias_pkey PRIMARY KEY (song_id, search_alias);

ALTER TABLE ONLY app.song_entry
    ADD CONSTRAINT song_entry_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.song_external_artist
    ADD CONSTRAINT song_external_artist_pkey PRIMARY KEY (song_id, artist_id);

ALTER TABLE ONLY app.song_external_artist
    ADD CONSTRAINT song_external_artist_song_id_position_key UNIQUE (song_id, "position");

ALTER TABLE ONLY app.song_member
    ADD CONSTRAINT song_member_pkey PRIMARY KEY (song_id, member_id);

ALTER TABLE ONLY app.song_member
    ADD CONSTRAINT song_member_song_id_position_key UNIQUE (song_id, "position");

ALTER TABLE ONLY app.special_event_review
    ADD CONSTRAINT special_event_review_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.special_event_review
    ADD CONSTRAINT special_event_review_song_id_key UNIQUE (song_id);

ALTER TABLE ONLY app.special_event_scan_state
    ADD CONSTRAINT special_event_scan_state_pkey PRIMARY KEY (singleton);

ALTER TABLE ONLY app.video
    ADD CONSTRAINT video_id_song_id_key UNIQUE (id, song_id);

ALTER TABLE ONLY app.video
    ADD CONSTRAINT video_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.video
    ADD CONSTRAINT video_youtube_id_key UNIQUE (youtube_id);

ALTER TABLE ONLY app.view_milestone_control
    ADD CONSTRAINT view_milestone_control_pkey PRIMARY KEY (singleton);

ALTER TABLE ONLY app.view_milestone_event
    ADD CONSTRAINT view_milestone_event_pkey PRIMARY KEY (video_id, target);

ALTER TABLE ONLY app.view_milestone_state
    ADD CONSTRAINT view_milestone_state_pkey PRIMARY KEY (video_id);

ALTER TABLE ONLY app.view_publication
    ADD CONSTRAINT view_publication_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.view_snapshot
    ADD CONSTRAINT view_snapshot_pkey PRIMARY KEY (video_id, logical_slot);

ALTER TABLE ONLY app.withdrawal_result
    ADD CONSTRAINT withdrawal_result_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.work_alias
    ADD CONSTRAINT work_alias_pkey PRIMARY KEY (work_id, search_alias);

ALTER TABLE ONLY app.work_artist
    ADD CONSTRAINT work_artist_pkey PRIMARY KEY (work_id, artist_id);

ALTER TABLE ONLY app.work_artist
    ADD CONSTRAINT work_artist_work_id_position_key UNIQUE (work_id, "position");

ALTER TABLE ONLY app.youtube_authorization
    ADD CONSTRAINT youtube_authorization_pkey PRIMARY KEY (user_id);

ALTER TABLE ONLY app.youtube_authorization
    ADD CONSTRAINT youtube_authorization_state_hash_key UNIQUE (state_hash);

ALTER TABLE ONLY app.youtube_connection
    ADD CONSTRAINT youtube_connection_pkey PRIMARY KEY (user_id);

ALTER TABLE ONLY app.youtube_export_item
    ADD CONSTRAINT youtube_export_item_pkey PRIMARY KEY (export_id, "position");

ALTER TABLE ONLY app.youtube_export
    ADD CONSTRAINT youtube_export_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.youtube_export
    ADD CONSTRAINT youtube_export_user_id_request_id_key UNIQUE (user_id, request_id);

ALTER TABLE ONLY app.youtube_public_schedule
    ADD CONSTRAINT youtube_public_schedule_pkey PRIMARY KEY (youtube_id);

ALTER TABLE ONLY session.spring_session_attributes
    ADD CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name);

ALTER TABLE ONLY session.spring_session
    ADD CONSTRAINT spring_session_pk PRIMARY KEY (primary_id);

CREATE INDEX account_reauthentication_expiry_idx ON app.account_reauthentication USING btree (expires_at);

CREATE INDEX admin_audit_review_idx ON app.admin_audit USING btree (review_id, changed_at DESC);

CREATE INDEX artist_registration_active_idx ON app.artist_registration_request USING btree (created_at) WHERE ((status)::text = 'PROCESSING'::text);

CREATE INDEX artist_registration_expiry_idx ON app.artist_registration_request USING btree (expires_at) WHERE ((status)::text <> 'EXPIRED'::text);

CREATE INDEX catalog_audit_target_idx ON app.catalog_audit USING btree (target_type, target_id, changed_at DESC, id);

CREATE INDEX collection_pending_idx ON app.collection_target USING btree (run_id, video_id) WHERE ((outcome)::text = 'PENDING'::text);

CREATE UNIQUE INDEX collection_retry_active_idx ON app.collection_retry_request USING btree ((true)) WHERE (status IN ('QUEUED', 'RUNNING'));

CREATE INDEX collection_retry_created_idx ON app.collection_retry_request USING btree (created_at DESC, id);

CREATE INDEX cover_auto_original_due_idx ON app.cover_auto_registration USING btree (original_lookup_after, review_id) WHERE (original_lookup_after IS NOT NULL);

CREATE INDEX cover_auto_registration_time_idx ON app.cover_auto_registration USING btree (processed_at DESC, review_id);

CREATE INDEX external_link_song_idx ON app.external_link USING btree (song_id);

CREATE INDEX external_link_work_idx ON app.external_link USING btree (work_id);

CREATE INDEX favorite_song_idx ON app.favorite USING btree (song_id);

CREATE INDEX favorite_user_saved_idx ON app.favorite USING btree (user_id, created_at DESC, song_id DESC);

CREATE UNIQUE INDEX member_slug_unique_idx ON app.member USING btree (slug);

CREATE INDEX milestone_due_idx ON app.view_milestone_state USING btree (next_check_at, video_id);

CREATE INDEX milestone_event_time_idx ON app.view_milestone_event USING btree (detected_at DESC, video_id);

CREATE INDEX persistent_login_expiry_idx ON app.persistent_login USING btree (expires_at);

CREATE INDEX persistent_login_user_idx ON app.persistent_login USING btree (user_id);

CREATE INDEX playlist_item_song_idx ON app.playlist_item USING btree (song_id);

CREATE UNIQUE INDEX playlist_public_id_unique_idx ON app.playlist USING btree (public_id);

CREATE INDEX playlist_user_created_idx ON app.playlist USING btree (user_id, created_at DESC, id DESC);

CREATE INDEX premiere_auto_original_due_idx ON app.premiere_auto_registration USING btree (original_lookup_after, review_id) WHERE (original_lookup_after IS NOT NULL);

CREATE INDEX review_deferred_idx ON app.review_item USING btree (source_observed_at, id) WHERE (((review_status)::text = 'PENDING'::text) AND ((disposition)::text = 'DEFERRED'::text));

CREATE INDEX review_queue_idx ON app.review_item USING btree (review_status, first_seen_at DESC, id DESC);

CREATE INDEX review_registered_batch_idx ON app.review_item USING btree (registered_batch_id) WHERE (registered_batch_id IS NOT NULL);

CREATE INDEX song_member_member_idx ON app.song_member USING btree (member_id, song_id) WHERE confirmed;

CREATE INDEX song_search_compact_title_idx ON app.song_entry USING btree (replace((search_title)::text, ' '::text, ''::text));

CREATE INDEX song_search_title_idx ON app.song_entry USING btree (search_title);

CREATE INDEX song_work_idx ON app.song_entry USING btree (work_id);

CREATE INDEX special_event_review_list_idx ON app.special_event_review USING btree (status, created_at DESC, id);

CREATE INDEX video_public_date_idx ON app.video USING btree (published_at DESC, id) WHERE ((availability)::text = 'PUBLIC'::text);

CREATE INDEX video_song_idx ON app.video USING btree (song_id);

CREATE INDEX view_snapshot_observed_idx ON app.view_snapshot USING btree (observed_at);

CREATE INDEX view_snapshot_video_observed_idx ON app.view_snapshot USING btree (video_id, observed_at DESC, logical_slot DESC);

CREATE INDEX withdrawal_result_expiry_idx ON app.withdrawal_result USING btree (expires_at);

CREATE UNIQUE INDEX youtube_export_active_user_idx ON app.youtube_export USING btree (user_id) WHERE (status IN ('QUEUED', 'RUNNING', 'FAILED', 'UNCERTAIN'));

CREATE INDEX youtube_export_pending_idx ON app.youtube_export USING btree (created_at) WHERE (status IN ('QUEUED', 'RUNNING'));

CREATE INDEX youtube_public_schedule_start_idx ON app.youtube_public_schedule USING btree (scheduled_start_at, youtube_id);

CREATE UNIQUE INDEX spring_session_ix1 ON session.spring_session USING btree (session_id);

CREATE INDEX spring_session_ix2 ON session.spring_session USING btree (expiry_time);

CREATE INDEX spring_session_ix3 ON session.spring_session USING btree (principal_name);

CREATE TRIGGER revoke_persistent_login_on_account_change AFTER UPDATE OF role, status ON app.app_user FOR EACH ROW WHEN ((((old.role)::text IS DISTINCT FROM (new.role)::text) OR ((old.status)::text IS DISTINCT FROM (new.status)::text))) EXECUTE FUNCTION app.revoke_persistent_login_on_account_change();

ALTER TABLE ONLY app.account_reauthentication
    ADD CONSTRAINT account_reauthentication_user_id_fkey FOREIGN KEY (user_id) REFERENCES app.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY app.admin_audit
    ADD CONSTRAINT admin_audit_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES app.app_user(id) ON DELETE SET NULL;

ALTER TABLE ONLY app.admin_audit
    ADD CONSTRAINT admin_audit_review_id_fkey FOREIGN KEY (review_id) REFERENCES app.review_item(id);

ALTER TABLE ONLY app.artist_alias
    ADD CONSTRAINT artist_alias_artist_id_fkey FOREIGN KEY (artist_id) REFERENCES app.artist(id);

ALTER TABLE ONLY app.artist_registration_request
    ADD CONSTRAINT artist_registration_request_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES app.app_user(id) ON DELETE SET NULL;

ALTER TABLE ONLY app.catalog_audit
    ADD CONSTRAINT catalog_audit_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES app.app_user(id) ON DELETE SET NULL;

ALTER TABLE ONLY app.catalog_state
    ADD CONSTRAINT catalog_state_view_publication_id_fkey FOREIGN KEY (view_publication_id) REFERENCES app.view_publication(id);

ALTER TABLE ONLY app.channel
    ADD CONSTRAINT channel_member_id_fkey FOREIGN KEY (member_id) REFERENCES app.member(id);

ALTER TABLE ONLY app.collection_run
    ADD CONSTRAINT collection_run_publication_id_fkey FOREIGN KEY (publication_id) REFERENCES app.view_publication(id) ON DELETE SET NULL;

ALTER TABLE ONLY app.collection_target
    ADD CONSTRAINT collection_target_run_id_fkey FOREIGN KEY (run_id) REFERENCES app.collection_run(id) ON DELETE CASCADE;

ALTER TABLE ONLY app.collection_target
    ADD CONSTRAINT collection_target_video_id_fkey FOREIGN KEY (video_id) REFERENCES app.video(id) ON DELETE CASCADE;

ALTER TABLE ONLY app.cover_auto_registration
    ADD CONSTRAINT cover_auto_registration_member_id_fkey FOREIGN KEY (member_id) REFERENCES app.member(id);

ALTER TABLE ONLY app.cover_auto_registration
    ADD CONSTRAINT cover_auto_registration_review_id_fkey FOREIGN KEY (review_id) REFERENCES app.review_item(id);

ALTER TABLE ONLY app.cover_auto_registration
    ADD CONSTRAINT cover_auto_registration_song_id_fkey FOREIGN KEY (song_id) REFERENCES app.song_entry(id);

ALTER TABLE ONLY app.cover_auto_registration
    ADD CONSTRAINT cover_auto_registration_video_id_fkey FOREIGN KEY (video_id) REFERENCES app.video(id);

ALTER TABLE ONLY app.daily_video_view
    ADD CONSTRAINT daily_video_view_video_id_fkey FOREIGN KEY (video_id) REFERENCES app.video(id) ON DELETE CASCADE;

ALTER TABLE ONLY app.discovery_channel_state
    ADD CONSTRAINT discovery_channel_state_channel_id_fkey FOREIGN KEY (channel_id) REFERENCES app.channel(id);

ALTER TABLE ONLY app.discovery_run
    ADD CONSTRAINT discovery_run_channel_id_fkey FOREIGN KEY (channel_id) REFERENCES app.channel(id);

ALTER TABLE ONLY app.external_link
    ADD CONSTRAINT external_link_song_id_fkey FOREIGN KEY (song_id) REFERENCES app.song_entry(id);

ALTER TABLE ONLY app.external_link
    ADD CONSTRAINT external_link_work_id_fkey FOREIGN KEY (work_id) REFERENCES app.musical_work(id);

ALTER TABLE ONLY app.favorite
    ADD CONSTRAINT favorite_song_id_fkey FOREIGN KEY (song_id) REFERENCES app.song_entry(id);

ALTER TABLE ONLY app.favorite
    ADD CONSTRAINT favorite_user_id_fkey FOREIGN KEY (user_id) REFERENCES app.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY app.karaoke_entry
    ADD CONSTRAINT karaoke_entry_song_id_fkey FOREIGN KEY (song_id) REFERENCES app.song_entry(id);

ALTER TABLE ONLY app.karaoke_entry
    ADD CONSTRAINT karaoke_entry_work_id_fkey FOREIGN KEY (work_id) REFERENCES app.musical_work(id);

ALTER TABLE ONLY app.member_alias
    ADD CONSTRAINT member_alias_member_id_fkey FOREIGN KEY (member_id) REFERENCES app.member(id);

ALTER TABLE ONLY app.persistent_login
    ADD CONSTRAINT persistent_login_user_id_fkey FOREIGN KEY (user_id) REFERENCES app.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY app.playlist_item
    ADD CONSTRAINT playlist_item_playlist_id_fkey FOREIGN KEY (playlist_id) REFERENCES app.playlist(id) ON DELETE CASCADE;

ALTER TABLE ONLY app.playlist_item
    ADD CONSTRAINT playlist_item_song_id_fkey FOREIGN KEY (song_id) REFERENCES app.song_entry(id);

ALTER TABLE ONLY app.playlist
    ADD CONSTRAINT playlist_user_id_fkey FOREIGN KEY (user_id) REFERENCES app.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY app.premiere_auto_registration
    ADD CONSTRAINT premiere_auto_registration_review_id_fkey FOREIGN KEY (review_id) REFERENCES app.review_item(id);

ALTER TABLE ONLY app.premiere_auto_registration
    ADD CONSTRAINT premiere_auto_registration_song_id_fkey FOREIGN KEY (song_id) REFERENCES app.song_entry(id);

ALTER TABLE ONLY app.premiere_auto_registration
    ADD CONSTRAINT premiere_auto_registration_video_id_fkey FOREIGN KEY (video_id) REFERENCES app.video(id);

ALTER TABLE ONLY app.published_video_view
    ADD CONSTRAINT published_video_view_publication_id_fkey FOREIGN KEY (publication_id) REFERENCES app.view_publication(id);

ALTER TABLE ONLY app.published_video_view
    ADD CONSTRAINT published_video_view_video_id_fkey FOREIGN KEY (video_id) REFERENCES app.video(id);

ALTER TABLE ONLY app.review_item
    ADD CONSTRAINT review_item_channel_id_fkey FOREIGN KEY (channel_id) REFERENCES app.channel(id);

ALTER TABLE ONLY app.review_item
    ADD CONSTRAINT review_item_registered_video_id_fkey FOREIGN KEY (registered_video_id) REFERENCES app.video(id);

ALTER TABLE ONLY app.song_alias
    ADD CONSTRAINT song_alias_song_id_fkey FOREIGN KEY (song_id) REFERENCES app.song_entry(id);

ALTER TABLE ONLY app.song_entry
    ADD CONSTRAINT song_entry_work_id_fkey FOREIGN KEY (work_id) REFERENCES app.musical_work(id);

ALTER TABLE ONLY app.song_external_artist
    ADD CONSTRAINT song_external_artist_artist_id_fkey FOREIGN KEY (artist_id) REFERENCES app.artist(id);

ALTER TABLE ONLY app.song_external_artist
    ADD CONSTRAINT song_external_artist_song_id_fkey FOREIGN KEY (song_id) REFERENCES app.song_entry(id);

ALTER TABLE ONLY app.song_member
    ADD CONSTRAINT song_member_member_id_fkey FOREIGN KEY (member_id) REFERENCES app.member(id);

ALTER TABLE ONLY app.song_member
    ADD CONSTRAINT song_member_song_id_fkey FOREIGN KEY (song_id) REFERENCES app.song_entry(id);

ALTER TABLE ONLY app.song_entry
    ADD CONSTRAINT song_representative_video_fk FOREIGN KEY (representative_video_id, id) REFERENCES app.video(id, song_id);

ALTER TABLE ONLY app.special_event_review
    ADD CONSTRAINT special_event_review_song_id_fkey FOREIGN KEY (song_id) REFERENCES app.song_entry(id) ON DELETE CASCADE;

ALTER TABLE ONLY app.video
    ADD CONSTRAINT video_channel_id_fkey FOREIGN KEY (channel_id) REFERENCES app.channel(id);

ALTER TABLE ONLY app.video
    ADD CONSTRAINT video_song_id_fkey FOREIGN KEY (song_id) REFERENCES app.song_entry(id);

ALTER TABLE ONLY app.view_milestone_event
    ADD CONSTRAINT view_milestone_event_video_id_fkey FOREIGN KEY (video_id) REFERENCES app.video(id) ON DELETE CASCADE;

ALTER TABLE ONLY app.view_milestone_state
    ADD CONSTRAINT view_milestone_state_video_id_fkey FOREIGN KEY (video_id) REFERENCES app.video(id) ON DELETE CASCADE;

ALTER TABLE ONLY app.view_snapshot
    ADD CONSTRAINT view_snapshot_video_id_fkey FOREIGN KEY (video_id) REFERENCES app.video(id) ON DELETE CASCADE;

ALTER TABLE ONLY app.withdrawal_result
    ADD CONSTRAINT withdrawal_result_user_id_fkey FOREIGN KEY (user_id) REFERENCES app.app_user(id) ON DELETE SET NULL;

ALTER TABLE ONLY app.work_alias
    ADD CONSTRAINT work_alias_work_id_fkey FOREIGN KEY (work_id) REFERENCES app.musical_work(id);

ALTER TABLE ONLY app.work_artist
    ADD CONSTRAINT work_artist_artist_id_fkey FOREIGN KEY (artist_id) REFERENCES app.artist(id);

ALTER TABLE ONLY app.work_artist
    ADD CONSTRAINT work_artist_work_id_fkey FOREIGN KEY (work_id) REFERENCES app.musical_work(id);

ALTER TABLE ONLY app.youtube_authorization
    ADD CONSTRAINT youtube_authorization_user_id_fkey FOREIGN KEY (user_id) REFERENCES app.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY app.youtube_connection
    ADD CONSTRAINT youtube_connection_user_id_fkey FOREIGN KEY (user_id) REFERENCES app.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY app.youtube_export_item
    ADD CONSTRAINT youtube_export_item_export_id_fkey FOREIGN KEY (export_id) REFERENCES app.youtube_export(id) ON DELETE CASCADE;

ALTER TABLE ONLY app.youtube_export
    ADD CONSTRAINT youtube_export_user_id_fkey FOREIGN KEY (user_id) REFERENCES app.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY app.youtube_public_schedule
    ADD CONSTRAINT youtube_public_schedule_channel_id_fkey FOREIGN KEY (channel_id) REFERENCES app.channel(id) ON DELETE CASCADE;

ALTER TABLE ONLY session.spring_session_attributes
    ADD CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id) REFERENCES session.spring_session(primary_id) ON DELETE CASCADE;

GRANT USAGE ON SCHEMA app TO "${runtimeRole}";

GRANT USAGE ON SCHEMA session TO "${runtimeRole}";

REVOKE ALL ON FUNCTION app.complete_review_candidates(candidates jsonb, selected jsonb, batch uuid, request_hash text, actor uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION app.complete_review_candidates(candidates jsonb, selected jsonb, batch uuid, request_hash text, actor uuid) TO "${runtimeRole}";

REVOKE ALL ON FUNCTION app.new_playlist_public_id() FROM PUBLIC;
GRANT ALL ON FUNCTION app.new_playlist_public_id() TO "${runtimeRole}";

REVOKE ALL ON FUNCTION app.publish_collected_song(candidate_id uuid, observed_at timestamp with time zone, participant_id uuid, participant_version bigint, display_title text, normalized_title text, can_embed boolean, publication_rule text, owner_token uuid, premiere boolean) FROM PUBLIC;

REVOKE ALL ON FUNCTION app.publish_completed_premiere(candidate_id uuid, observed_at timestamp with time zone, participant_id uuid, participant_version bigint, normalized_title text, can_embed boolean, publication_rule text, owner_token uuid) FROM PUBLIC;

REVOKE ALL ON FUNCTION app.publish_discovered_cover(candidate_id uuid, observed_at timestamp with time zone, participant_id uuid, participant_version bigint, normalized_title text, can_embed boolean, publication_rule text, owner_token uuid) FROM PUBLIC;

REVOKE ALL ON FUNCTION app.revoke_persistent_login_on_account_change() FROM PUBLIC;

REVOKE ALL ON FUNCTION app.withdraw_account(account_id uuid, current_session_hash text) FROM PUBLIC;
GRANT ALL ON FUNCTION app.withdraw_account(account_id uuid, current_session_hash text) TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE app.account_reauthentication TO "${runtimeRole}";

GRANT INSERT ON TABLE app.admin_audit TO "${runtimeRole}";

GRANT SELECT ON TABLE app.app_user TO "${runtimeRole}";

GRANT INSERT(id) ON TABLE app.app_user TO "${runtimeRole}";

GRANT INSERT(google_subject) ON TABLE app.app_user TO "${runtimeRole}";

GRANT INSERT(email),UPDATE(email) ON TABLE app.app_user TO "${runtimeRole}";

GRANT UPDATE(last_login_at) ON TABLE app.app_user TO "${runtimeRole}";

GRANT SELECT,INSERT,UPDATE ON TABLE app.artist TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE ON TABLE app.artist_alias TO "${runtimeRole}";

GRANT SELECT,INSERT ON TABLE app.artist_registration_request TO "${runtimeRole}";

GRANT UPDATE(status) ON TABLE app.artist_registration_request TO "${runtimeRole}";

GRANT UPDATE(artist_id) ON TABLE app.artist_registration_request TO "${runtimeRole}";

GRANT UPDATE(error_code) ON TABLE app.artist_registration_request TO "${runtimeRole}";

GRANT UPDATE(error_status) ON TABLE app.artist_registration_request TO "${runtimeRole}";

GRANT UPDATE(finished_at) ON TABLE app.artist_registration_request TO "${runtimeRole}";

GRANT SELECT,INSERT ON TABLE app.catalog_audit TO "${runtimeRole}";

GRANT SELECT ON TABLE app.catalog_state TO "${runtimeRole}";

GRANT SELECT,INSERT,UPDATE ON TABLE app.channel TO "${runtimeRole}";

GRANT SELECT ON TABLE app.collection_retry_request TO "${runtimeRole}";

GRANT INSERT(id) ON TABLE app.collection_retry_request TO "${runtimeRole}";

GRANT INSERT(kind) ON TABLE app.collection_retry_request TO "${runtimeRole}";

GRANT INSERT(run_id) ON TABLE app.collection_retry_request TO "${runtimeRole}";

GRANT INSERT(expected_attempt) ON TABLE app.collection_retry_request TO "${runtimeRole}";

GRANT SELECT ON TABLE app.collection_rule TO "${runtimeRole}";

GRANT UPDATE(version) ON TABLE app.collection_rule TO "${runtimeRole}";

GRANT UPDATE(configuration) ON TABLE app.collection_rule TO "${runtimeRole}";

GRANT UPDATE(edited_at) ON TABLE app.collection_rule TO "${runtimeRole}";

GRANT SELECT ON TABLE app.collection_run TO "${runtimeRole}";

GRANT SELECT ON TABLE app.collection_target TO "${runtimeRole}";

GRANT SELECT ON TABLE app.cover_auto_registration TO "${runtimeRole}";

GRANT UPDATE(original_lookup_after) ON TABLE app.cover_auto_registration TO "${runtimeRole}";

GRANT UPDATE(original_lookup_attempts) ON TABLE app.cover_auto_registration TO "${runtimeRole}";

GRANT SELECT ON TABLE app.cover_publication_control TO "${runtimeRole}";

GRANT UPDATE(enabled) ON TABLE app.cover_publication_control TO "${runtimeRole}";

GRANT UPDATE(version) ON TABLE app.cover_publication_control TO "${runtimeRole}";

GRANT UPDATE(edited_at) ON TABLE app.cover_publication_control TO "${runtimeRole}";

GRANT SELECT ON TABLE app.daily_video_view TO "${runtimeRole}";

GRANT SELECT ON TABLE app.discovery_channel_state TO "${runtimeRole}";

GRANT SELECT ON TABLE app.discovery_run TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE ON TABLE app.external_link TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE ON TABLE app.favorite TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE ON TABLE app.karaoke_entry TO "${runtimeRole}";

GRANT SELECT,INSERT,UPDATE ON TABLE app.member TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE ON TABLE app.member_alias TO "${runtimeRole}";

GRANT SELECT,INSERT,UPDATE ON TABLE app.musical_work TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE app.persistent_login TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE app.playlist TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE app.playlist_item TO "${runtimeRole}";

GRANT SELECT ON TABLE app.premiere_auto_registration TO "${runtimeRole}";

GRANT UPDATE(original_lookup_after) ON TABLE app.premiere_auto_registration TO "${runtimeRole}";

GRANT UPDATE(original_lookup_attempts) ON TABLE app.premiere_auto_registration TO "${runtimeRole}";

GRANT SELECT ON TABLE app.published_video_view TO "${runtimeRole}";

GRANT SELECT ON TABLE app.review_item TO "${runtimeRole}";

GRANT UPDATE(review_status) ON TABLE app.review_item TO "${runtimeRole}";

GRANT UPDATE(review_note) ON TABLE app.review_item TO "${runtimeRole}";

GRANT UPDATE(reviewed_at) ON TABLE app.review_item TO "${runtimeRole}";

GRANT UPDATE(version) ON TABLE app.review_item TO "${runtimeRole}";

GRANT UPDATE(registered_video_id) ON TABLE app.review_item TO "${runtimeRole}";

GRANT UPDATE(registered_batch_id) ON TABLE app.review_item TO "${runtimeRole}";

GRANT UPDATE(registered_batch_hash) ON TABLE app.review_item TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE ON TABLE app.song_alias TO "${runtimeRole}";

GRANT SELECT,INSERT,UPDATE ON TABLE app.song_entry TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE ON TABLE app.song_external_artist TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE ON TABLE app.song_member TO "${runtimeRole}";

GRANT SELECT,INSERT ON TABLE app.special_event_review TO "${runtimeRole}";

GRANT UPDATE(status) ON TABLE app.special_event_review TO "${runtimeRole}";

GRANT UPDATE(evidence) ON TABLE app.special_event_review TO "${runtimeRole}";

GRANT UPDATE(active) ON TABLE app.special_event_review TO "${runtimeRole}";

GRANT UPDATE(source_expires_at) ON TABLE app.special_event_review TO "${runtimeRole}";

GRANT UPDATE(version) ON TABLE app.special_event_review TO "${runtimeRole}";

GRANT UPDATE(updated_at) ON TABLE app.special_event_review TO "${runtimeRole}";

GRANT SELECT ON TABLE app.special_event_scan_state TO "${runtimeRole}";

GRANT UPDATE(last_song_id) ON TABLE app.special_event_scan_state TO "${runtimeRole}";

GRANT SELECT,INSERT ON TABLE app.video TO "${runtimeRole}";

GRANT UPDATE(video_kind) ON TABLE app.video TO "${runtimeRole}";

GRANT UPDATE(published_at) ON TABLE app.video TO "${runtimeRole}";

GRANT UPDATE(thumbnail_url) ON TABLE app.video TO "${runtimeRole}";

GRANT UPDATE(version) ON TABLE app.video TO "${runtimeRole}";

GRANT UPDATE(edited_at) ON TABLE app.video TO "${runtimeRole}";

GRANT SELECT ON TABLE app.view_milestone_event TO "${runtimeRole}";

GRANT SELECT ON TABLE app.view_milestone_state TO "${runtimeRole}";

GRANT SELECT ON TABLE app.view_publication TO "${runtimeRole}";

GRANT SELECT ON TABLE app.view_snapshot TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE app.withdrawal_result TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE ON TABLE app.work_alias TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE ON TABLE app.work_artist TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE app.youtube_authorization TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE app.youtube_connection TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE app.youtube_export TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE app.youtube_export_item TO "${runtimeRole}";

GRANT SELECT ON TABLE app.youtube_public_schedule TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE session.spring_session TO "${runtimeRole}";

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE session.spring_session_attributes TO "${runtimeRole}";

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

-- Optional collector grants retain the former conditional behavior.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='stelody_collector') THEN
        GRANT ALL ON FUNCTION app.publish_collected_song(candidate_id uuid, observed_at timestamp with time zone, participant_id uuid, participant_version bigint, display_title text, normalized_title text, can_embed boolean, publication_rule text, owner_token uuid, premiere boolean) TO stelody_collector;
        GRANT ALL ON FUNCTION app.publish_completed_premiere(candidate_id uuid, observed_at timestamp with time zone, participant_id uuid, participant_version bigint, normalized_title text, can_embed boolean, publication_rule text, owner_token uuid) TO stelody_collector;
        GRANT SELECT ON TABLE app.song_entry TO stelody_collector;
        GRANT SELECT ON TABLE app.song_external_artist TO stelody_collector;
        GRANT SELECT ON TABLE app.song_member TO stelody_collector;
        GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE app.view_milestone_control TO stelody_collector;
        GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE app.view_milestone_event TO stelody_collector;
        GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE app.view_milestone_state TO stelody_collector;
        GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE app.youtube_public_schedule TO stelody_collector;
    END IF;
END $$;

INSERT INTO app.member (id, name, search_name, generation, activity_status, profile_image_url, debut_date, version, edited_at, birthday_month, birthday_day, unit_name, chzzk_url, x_url, display_order, slug) VALUES
	('e45fafbd-eda4-5d6c-b67b-b41999b2ad03', '시즈키 토아', '시즈키 토아', 4, 'ACTIVE', NULL, '2026-10-17', 1, CURRENT_TIMESTAMP, 11, 22, 'ABYSS', 'https://chzzk.naver.com/2299fad4bebb504b0978e1ec08b2f7d3', 'https://x.com/ShizukiToa_', 1, 'toa'),
	('b715a5e0-b10f-5a79-8405-9703e0543716', '코가네 랑', '코가네 랑', 4, 'ACTIVE', NULL, '2026-10-17', 1, CURRENT_TIMESTAMP, 8, 22, 'ABYSS', 'https://chzzk.naver.com/c657aa3592260a2b59669a72ec151936', 'https://x.com/CoganeRang', 2, 'rang'),
	('4fdb54c3-bfd5-5ef8-9b04-2e016b33d475', '하나요 히요리', '하나요 히요리', 4, 'ACTIVE', NULL, '2026-10-18', 1, CURRENT_TIMESTAMP, 3, 31, 'ABYSS', 'https://chzzk.naver.com/49d2c3cf8f0b2843d934ec15e82bb54c', 'https://x.com/HanayoHiyori', 3, 'hiyori'),
	('6ac3f9a8-90d3-5f90-91fb-37ccf645a8b9', '토키노 메리', '토키노 메리', 4, 'ACTIVE', NULL, '2026-10-18', 1, CURRENT_TIMESTAMP, 6, 13, 'ABYSS', 'https://chzzk.naver.com/792acf9ca28b42918710327e14b06b68', 'https://x.com/TokinoMary', 4, 'mary');

INSERT INTO app.channel (id, youtube_id, name, member_id, channel_type, collection_enabled, version, edited_at) VALUES
	('eaf4a97c-cdb6-525c-888c-8080a4610ac9', 'UClcSEuDF_g_6g4snw_nAavw', '시즈키 토아', 'e45fafbd-eda4-5d6c-b67b-b41999b2ad03', 'MEMBER', true, 0, NULL),
	('2c9301cb-6dd0-5e57-a8a4-13874c9f94d9', 'UCQ6LlsI7Bbj2yhSqLMI1gGA', '코가네 랑', 'b715a5e0-b10f-5a79-8405-9703e0543716', 'MEMBER', true, 0, NULL),
	('2d255d16-213c-5696-87a8-c6c3c20f25ad', 'UCHAr9pWGS30akL4MQCQT4uQ', '하나요 히요리', '4fdb54c3-bfd5-5ef8-9b04-2e016b33d475', 'MEMBER', true, 0, NULL),
	('da0f59c4-75ad-5df2-9006-5269b02f8d96', 'UCGSt1yNG5l-jpYRLpJ5oNtA', '토키노 메리', '6ac3f9a8-90d3-5f90-91fb-37ccf645a8b9', 'MEMBER', true, 0, NULL);

INSERT INTO app.catalog_state (singleton, view_publication_id) VALUES
	(true, NULL);

INSERT INTO app.collection_control (singleton, owner_token) VALUES
	(true, NULL);

INSERT INTO app.collection_rule (id, version, configuration, edited_at) VALUES
	('00000000-0000-0000-0000-000000000001', 0, '{"cover": [{"text": "cover", "match": "WORD"}, {"text": "covered by", "match": "WORD"}, {"text": "歌ってみた", "match": "PHRASE"}], "exclude": [{"text": "shorts", "match": "WORD"}, {"text": "clip", "match": "WORD"}, {"text": "clips", "match": "WORD"}, {"text": "livestream", "match": "WORD"}, {"text": "[클립]", "match": "PHRASE"}, {"text": "[방송]", "match": "PHRASE"}, {"text": "[다시보기]", "match": "PHRASE"}], "original": [{"text": "original", "match": "WORD"}, {"text": "mv", "match": "WORD"}, {"text": "오리지널", "match": "PHRASE"}]}', CURRENT_TIMESTAMP);

INSERT INTO app.cover_publication_control (singleton, enabled, version, edited_at) VALUES
	(true, false, 0, CURRENT_TIMESTAMP);

INSERT INTO app.special_event_scan_state (singleton, last_song_id) VALUES
	(true, NULL);

INSERT INTO app.view_milestone_control (singleton, retry_after, last_error_code) VALUES
	(true, NULL, NULL);
