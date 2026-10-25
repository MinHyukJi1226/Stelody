-- Only these bounded operations may delete review candidates. Neither runtime receives DELETE.
-- Retain the earliest scheduled auto-publication batch awaiting cleanup, across retries/channels.
ALTER TABLE app.discovery_channel_state ADD COLUMN scan_started_at timestamptz;
CREATE FUNCTION app.complete_review_candidates(candidates jsonb, actor uuid)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE targets uuid[]; removed integer;
BEGIN
    IF NOT EXISTS(SELECT 1 FROM app.app_user WHERE id=actor AND role='ADMIN' AND status='ACTIVE') THEN
        RETURN -2;
    END IF;
    IF jsonb_typeof(candidates) IS DISTINCT FROM 'array' OR jsonb_array_length(candidates)>10000 THEN
        RAISE EXCEPTION 'INVALID_REVIEW_REQUEST';
    END IF;
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
REVOKE ALL ON FUNCTION app.complete_review_candidates(jsonb,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.complete_review_candidates(jsonb,uuid) TO "${runtimeRole}";

CREATE FUNCTION app.cleanup_discovery_reviews(discovery uuid, owner_token uuid)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE targets uuid[]; removed integer; scan app.discovery_run%ROWTYPE;
BEGIN
    PERFORM singleton FROM app.collection_control c
      WHERE c.singleton AND c.owner_token=cleanup_discovery_reviews.owner_token FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'LOCK_LOST'; END IF;
    SELECT * INTO scan FROM app.discovery_run WHERE id=discovery;
    IF NOT FOUND OR scan.mode<>'NEW' OR scan.status<>'SUCCEEDED' THEN RETURN 0; END IF;
    PERFORM singleton FROM app.cover_publication_control WHERE singleton AND enabled FOR SHARE;
    IF NOT FOUND THEN RETURN 0; END IF;
    IF EXISTS(SELECT 1 FROM app.discovery_channel_state s JOIN app.channel c ON c.id=s.channel_id
      WHERE c.collection_enabled AND c.channel_type IN ('GROUP','MEMBER') AND s.in_progress
        AND (scan.channel_id IS NULL OR c.id=scan.channel_id)) THEN RETURN 0; END IF;
    -- Only this successful scan's observations on fully completed channels are eligible.
    SELECT array_agg(id) INTO targets FROM (
      SELECT r.id FROM app.review_item r JOIN app.discovery_channel_state s ON s.channel_id=r.channel_id
        JOIN app.channel c ON c.id=r.channel_id
      WHERE r.review_status='PENDING' AND r.disposition='REVIEW' AND r.registered_video_id IS NULL
        AND s.scan_started_at IS NOT NULL AND r.source_observed_at>=s.scan_started_at AND r.source_observed_at<=scan.finished_at
        AND r.source_observed_at>CURRENT_TIMESTAMP-INTERVAL '30 days'
        AND NOT s.in_progress
        AND c.collection_enabled AND c.channel_type IN ('GROUP','MEMBER')
        AND (scan.channel_id IS NULL OR r.channel_id=scan.channel_id)
        AND NOT EXISTS(SELECT 1 FROM app.video v WHERE v.youtube_id=r.youtube_id)
        AND NOT EXISTS(SELECT 1 FROM app.cover_auto_registration a WHERE a.review_id=r.id)
      ORDER BY r.id FOR UPDATE OF r
    ) locked;
    DELETE FROM app.admin_audit WHERE review_id=ANY(targets);
    DELETE FROM app.review_item WHERE id=ANY(targets);
    GET DIAGNOSTICS removed=ROW_COUNT;
    UPDATE app.discovery_channel_state SET scan_started_at=NULL
      WHERE scan_started_at IS NOT NULL AND NOT in_progress
        AND EXISTS(SELECT 1 FROM app.channel c WHERE c.id=channel_id AND c.collection_enabled AND c.channel_type IN ('GROUP','MEMBER'))
        AND (scan.channel_id IS NULL OR channel_id=scan.channel_id);
    RETURN removed;
END;
$$;
REVOKE ALL ON FUNCTION app.cleanup_discovery_reviews(uuid,uuid) FROM PUBLIC;
