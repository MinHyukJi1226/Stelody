-- Run only as the schema owner after the backup role/schema preflight succeeds.
-- A registered candidate's source copy is redundant once its linked video has
-- the same YouTube identity and an equally recent (or newer) source observation.
-- Keep review identity, decisions, registration links, receipts and audit history.
WITH cleared AS (
    UPDATE app.review_item r
       SET source_title=NULL,
           source_published_at=NULL,
           source_thumbnail_url=NULL,
           source_duration_seconds=NULL,
           version=r.version+1
      FROM app.video v
     WHERE r.review_status='REGISTERED'
       AND r.registered_video_id=v.id
       AND r.youtube_id=v.youtube_id
       AND v.source_observed_at>=r.source_observed_at
       AND v.source_title<>''
       AND (r.source_title IS NOT NULL OR r.source_published_at IS NOT NULL
            OR r.source_thumbnail_url IS NOT NULL OR r.source_duration_seconds IS NOT NULL)
    RETURNING r.id
)
SELECT count(*) FROM cleared;
