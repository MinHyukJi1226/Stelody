-- Rebuildable observations and short-lived homepage news; no account data.
CREATE TABLE app.view_milestone_state (
    video_id UUID PRIMARY KEY REFERENCES app.video(id) ON DELETE CASCADE,
    view_count BIGINT NOT NULL CHECK (view_count>=0),
    observed_at TIMESTAMPTZ NOT NULL,
    highest_target BIGINT NOT NULL CHECK (highest_target>=0 AND highest_target%100000=0),
    available BOOLEAN NOT NULL DEFAULT true,
    estimated_at TIMESTAMPTZ,
    flat_checks SMALLINT NOT NULL DEFAULT 0 CHECK(flat_checks BETWEEN 0 AND 2),
    next_check_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX milestone_due_idx ON app.view_milestone_state(next_check_at,video_id);
CREATE TABLE app.view_milestone_event (
    video_id UUID NOT NULL REFERENCES app.video(id) ON DELETE CASCADE,
    target BIGINT NOT NULL CHECK (target>=100000 AND target%100000=0),
    detected_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(video_id,target)
);
CREATE INDEX milestone_event_time_idx ON app.view_milestone_event(detected_at DESC,video_id);
CREATE TABLE app.view_milestone_control (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK(singleton),
    retry_after TIMESTAMPTZ,
    last_error_code VARCHAR(64)
);
INSERT INTO app.view_milestone_control(singleton) VALUES(true);
REVOKE ALL ON app.view_milestone_state,app.view_milestone_event,app.view_milestone_control FROM PUBLIC;
GRANT SELECT ON app.view_milestone_state,app.view_milestone_event TO "${runtimeRole}";
DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='stelody_collector') THEN
        GRANT SELECT ON app.song_entry,app.song_member,app.song_external_artist TO stelody_collector;
        GRANT SELECT,INSERT,UPDATE,DELETE ON app.view_milestone_state,app.view_milestone_event,app.view_milestone_control TO stelody_collector;
    END IF;
END $$;
