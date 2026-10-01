CREATE TABLE app.discovery_channel_state (
    channel_id UUID PRIMARY KEY REFERENCES app.channel(id),
    uploads_id VARCHAR(150) NOT NULL,
    head_video_id VARCHAR(11),
    pending_head_id VARCHAR(11),
    page_token VARCHAR(2048),
    in_progress BOOLEAN NOT NULL DEFAULT false,
    backfill_token VARCHAR(2048),
    backfill_complete BOOLEAN NOT NULL DEFAULT false,
    last_scanned_at TIMESTAMPTZ
);
CREATE TABLE app.discovery_run (
    id UUID PRIMARY KEY,
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('NEW', 'BACKFILL')),
    channel_id UUID REFERENCES app.channel(id),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    status VARCHAR(24) NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','QUOTA_EXHAUSTED','TIMED_OUT')),
    pages INTEGER NOT NULL DEFAULT 0 CHECK (pages >= 0),
    candidates INTEGER NOT NULL DEFAULT 0 CHECK (candidates >= 0),
    error_code VARCHAR(64)
);
CREATE TABLE app.review_item (
    id UUID PRIMARY KEY,
    youtube_id VARCHAR(11) NOT NULL UNIQUE CHECK (youtube_id ~ '^[A-Za-z0-9_-]{11}$'),
    channel_id UUID NOT NULL REFERENCES app.channel(id),
    source_title VARCHAR(500),
    source_published_at TIMESTAMPTZ,
    source_thumbnail_url TEXT,
    source_duration_seconds BIGINT CHECK (source_duration_seconds >= 0),
    source_observed_at TIMESTAMPTZ NOT NULL,
    availability VARCHAR(16) NOT NULL CHECK (availability IN ('PUBLIC','UNLISTED','PRIVATE','DELETED','UNAVAILABLE')),
    disposition VARCHAR(16) NOT NULL CHECK (disposition IN ('REVIEW','EXCLUDED','DEFERRED')),
    suggested_type VARCHAR(16) NOT NULL CHECK (suggested_type IN ('UNKNOWN','COVER','ORIGINAL')),
    rule_version VARCHAR(60) NOT NULL,
    decision_reason VARCHAR(64) NOT NULL,
    review_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (review_status IN ('PENDING','IGNORED')),
    review_note VARCHAR(500),
    reviewed_at TIMESTAMPTZ,
    first_seen_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);
CREATE INDEX review_queue_idx ON app.review_item(review_status, first_seen_at DESC, id DESC);
CREATE INDEX review_deferred_idx ON app.review_item(source_observed_at, id) WHERE review_status='PENDING' AND disposition='DEFERRED';
GRANT SELECT ON app.review_item, app.discovery_channel_state, app.discovery_run TO "${runtimeRole}";
