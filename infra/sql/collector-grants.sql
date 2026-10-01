-- Run as the schema owner after V8. Provision this login separately with a private password.
-- Change stelody_collector here if the actual PostgreSQL role has another name.
GRANT USAGE ON SCHEMA app TO stelody_collector;
GRANT SELECT ON app.channel, app.video, app.catalog_state TO stelody_collector;
GRANT UPDATE (source_title, source_published_at, source_thumbnail_url, source_duration_seconds,
    source_observed_at, status_observed_at, availability, embeddable)
    ON app.video TO stelody_collector;
GRANT UPDATE (view_publication_id) ON app.catalog_state TO stelody_collector;
GRANT SELECT, INSERT, UPDATE, DELETE ON app.collection_run, app.collection_target,
    app.collection_control, app.view_snapshot, app.daily_video_view TO stelody_collector;
GRANT SELECT, INSERT, DELETE ON app.view_publication, app.published_video_view TO stelody_collector;
