-- A short-lived cache of public YouTube schedule metadata.
CREATE TABLE app.youtube_public_schedule (
    youtube_id VARCHAR(11) PRIMARY KEY CHECK (youtube_id ~ '^[A-Za-z0-9_-]{11}$'),
    channel_id UUID NOT NULL REFERENCES app.channel(id) ON DELETE CASCADE,
    title VARCHAR(500) NOT NULL CHECK (btrim(title) <> ''),
    scheduled_start_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CHECK (scheduled_start_at > observed_at)
);
CREATE INDEX youtube_public_schedule_start_idx
    ON app.youtube_public_schedule(scheduled_start_at, youtube_id);
REVOKE ALL ON app.youtube_public_schedule FROM PUBLIC;
GRANT SELECT ON app.youtube_public_schedule TO "${runtimeRole}";
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stelody_collector') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON app.youtube_public_schedule TO stelody_collector;
    END IF;
END $$;
