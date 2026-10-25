-- Execute as the restored schema owner, before any web/collector process starts.
BEGIN;
-- Backups omit these rows. Reset pointers/leases before allowing new observations.
UPDATE app.catalog_state SET view_publication_id=NULL;
DELETE FROM app.view_publication;
UPDATE app.collection_control SET owner_token=NULL;
UPDATE app.discovery_channel_state SET in_progress=false,page_token=NULL,pending_head_id=NULL;
UPDATE app.cover_publication_control SET enabled=false,version=version+1,edited_at=CURRENT_TIMESTAMP;
-- Age is measured from observation, never from restoration time.
UPDATE app.video SET source_title='',source_published_at=NULL,source_thumbnail_url=NULL,
    source_duration_seconds=NULL,source_observed_at=NULL
    WHERE source_observed_at<CURRENT_TIMESTAMP-INTERVAL '30 days';
UPDATE app.video SET availability='UNAVAILABLE',embeddable=false,status_observed_at=NULL
    WHERE status_observed_at<CURRENT_TIMESTAMP-INTERVAL '30 days';
UPDATE app.review_item SET source_title=NULL,source_published_at=NULL,source_thumbnail_url=NULL,
    source_duration_seconds=NULL,availability='UNAVAILABLE',disposition='DEFERRED',suggested_type='UNKNOWN',
    decision_reason='SOURCE_EXPIRED',version=version+1
    WHERE source_observed_at<CURRENT_TIMESTAMP-INTERVAL '30 days' AND decision_reason<>'SOURCE_EXPIRED';
UPDATE app.special_event_review SET evidence='[]'::jsonb,active=false,version=version+1
    WHERE source_expires_at<CURRENT_TIMESTAMP;
COMMIT;
