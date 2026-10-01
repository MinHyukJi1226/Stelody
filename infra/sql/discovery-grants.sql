-- Apply as schema owner after V9; keeps the V8-only grant script usable.
GRANT SELECT, INSERT, UPDATE, DELETE ON app.discovery_channel_state, app.discovery_run TO stelody_collector;
GRANT SELECT (id, youtube_id, channel_id, source_title, source_published_at, source_thumbnail_url,
    source_duration_seconds, source_observed_at, availability, disposition, suggested_type,
    rule_version, decision_reason, review_status, first_seen_at, version) ON app.review_item TO stelody_collector;
GRANT INSERT (id, youtube_id, channel_id, source_title, source_published_at, source_thumbnail_url,
    source_duration_seconds, source_observed_at, availability, disposition, suggested_type,
    rule_version, decision_reason, first_seen_at) ON app.review_item TO stelody_collector;
GRANT UPDATE (source_title, source_published_at, source_thumbnail_url, source_duration_seconds,
    source_observed_at, availability, disposition, suggested_type, rule_version, decision_reason, version)
    ON app.review_item TO stelody_collector;
