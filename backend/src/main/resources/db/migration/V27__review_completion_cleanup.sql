-- Replay information lives with registered candidates; no tokens or session authority are stored.
ALTER TABLE app.review_item ADD COLUMN registered_batch_id uuid,
    ADD COLUMN registered_batch_hash varchar(64),
    ADD CONSTRAINT review_batch_receipt_check CHECK (
        (registered_batch_id IS NULL AND registered_batch_hash IS NULL)
        OR (registered_batch_id IS NOT NULL AND registered_batch_hash IS NOT NULL
            AND registered_batch_hash ~ '^[0-9a-f]{64}$' AND review_status='REGISTERED'));
GRANT UPDATE (registered_batch_id,registered_batch_hash) ON app.review_item TO "${runtimeRole}";
CREATE INDEX review_registered_batch_idx ON app.review_item(registered_batch_id) WHERE registered_batch_id IS NOT NULL;

CREATE FUNCTION app.complete_review_candidates(candidates jsonb, selected jsonb, batch uuid, request_hash text, actor uuid)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
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
REVOKE ALL ON FUNCTION app.complete_review_candidates(jsonb,jsonb,uuid,text,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.complete_review_candidates(jsonb,jsonb,uuid,text,uuid) TO "${runtimeRole}";
