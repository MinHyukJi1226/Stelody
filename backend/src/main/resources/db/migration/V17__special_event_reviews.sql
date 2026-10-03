CREATE TABLE app.special_event_review (
    id UUID PRIMARY KEY,
    song_id UUID NOT NULL UNIQUE REFERENCES app.song_entry(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','CONFIRMED','DISMISSED')),
    evidence JSONB NOT NULL CHECK (jsonb_typeof(evidence) = 'array'),
    active BOOLEAN NOT NULL DEFAULT true,
    source_expires_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX special_event_review_list_idx ON app.special_event_review(status,created_at DESC,id);
CREATE TABLE app.special_event_scan_state (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    last_song_id UUID
);
INSERT INTO app.special_event_scan_state(singleton) VALUES (true);
GRANT SELECT, INSERT ON app.special_event_review TO "${runtimeRole}";
GRANT UPDATE (status,evidence,active,source_expires_at,version,updated_at) ON app.special_event_review TO "${runtimeRole}";
GRANT SELECT ON app.special_event_scan_state TO "${runtimeRole}";
GRANT UPDATE (last_song_id) ON app.special_event_scan_state TO "${runtimeRole}";
