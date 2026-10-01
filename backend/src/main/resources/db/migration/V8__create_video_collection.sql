ALTER TABLE app.video
    ADD COLUMN source_published_at TIMESTAMPTZ,
    ADD COLUMN source_thumbnail_url TEXT,
    ADD COLUMN source_duration_seconds BIGINT CHECK (source_duration_seconds >= 0),
    ADD COLUMN source_observed_at TIMESTAMPTZ,
    ADD COLUMN status_observed_at TIMESTAMPTZ;

CREATE TABLE app.collection_run (
    id UUID PRIMARY KEY,
    logical_slot TIMESTAMPTZ NOT NULL UNIQUE,
    status VARCHAR(24) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'QUOTA_EXHAUSTED', 'TIMED_OUT')),
    attempt INTEGER NOT NULL CHECK (attempt > 0),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    error_code VARCHAR(64),
    publication_id UUID REFERENCES app.view_publication(id) ON DELETE SET NULL,
    CHECK (logical_slot = date_trunc('hour', logical_slot AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')
);
CREATE TABLE app.collection_control (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    owner_token UUID
);
INSERT INTO app.collection_control(singleton) VALUES (true);
CREATE TABLE app.collection_target (
    run_id UUID NOT NULL REFERENCES app.collection_run(id) ON DELETE CASCADE,
    video_id UUID NOT NULL REFERENCES app.video(id) ON DELETE CASCADE,
    youtube_id VARCHAR(11) NOT NULL,
    channel_youtube_id VARCHAR(24) NOT NULL,
    outcome VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (outcome IN ('PENDING', 'OBSERVED', 'SKIPPED')),
    observed_at TIMESTAMPTZ,
    view_count BIGINT CHECK (view_count >= 0),
    PRIMARY KEY (run_id, video_id)
);
CREATE INDEX collection_pending_idx ON app.collection_target(run_id, video_id) WHERE outcome = 'PENDING';
CREATE TABLE app.view_snapshot (
    video_id UUID NOT NULL REFERENCES app.video(id) ON DELETE CASCADE,
    logical_slot TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    view_count BIGINT NOT NULL CHECK (view_count >= 0),
    source VARCHAR(32) NOT NULL DEFAULT 'YOUTUBE_DATA_API_V3',
    PRIMARY KEY (video_id, logical_slot)
);
CREATE INDEX view_snapshot_observed_idx ON app.view_snapshot(observed_at);
CREATE TABLE app.daily_video_view (
    video_id UUID NOT NULL REFERENCES app.video(id) ON DELETE CASCADE,
    day DATE NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    view_count BIGINT NOT NULL CHECK (view_count >= 0),
    source VARCHAR(32) NOT NULL DEFAULT 'YOUTUBE_DATA_API_V3',
    PRIMARY KEY (video_id, day)
);
-- Collector privileges are provisioned separately; normal web migrations need no collector login.
GRANT SELECT ON app.collection_run TO "${runtimeRole}";
