ALTER TABLE app.video ADD COLUMN view_collection_started_at TIMESTAMPTZ;

-- This is our collection start time, not an archived view count. Existing installations
-- can recover only the earliest observation still retained when this migration runs.
UPDATE app.video v SET view_collection_started_at = first.observed_at
FROM (
    SELECT video_id, min(observed_at) AS observed_at FROM (
        SELECT video_id, observed_at FROM app.view_snapshot
        UNION ALL SELECT video_id, observed_at FROM app.daily_video_view
        UNION ALL SELECT video_id, observed_at FROM app.published_video_view
    ) observations GROUP BY video_id
) first WHERE v.id = first.video_id;

CREATE INDEX view_snapshot_video_observed_idx
    ON app.view_snapshot(video_id, observed_at DESC, logical_slot DESC);
GRANT SELECT ON app.view_snapshot, app.daily_video_view TO "${runtimeRole}";
