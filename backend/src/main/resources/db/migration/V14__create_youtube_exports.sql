CREATE TABLE app.youtube_connection (
    user_id UUID PRIMARY KEY REFERENCES app.app_user(id) ON DELETE CASCADE,
    generation UUID NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN ('DISCONNECTED', 'CONNECTED', 'RECONNECT_REQUIRED', 'REVOKING')),
    access_token TEXT,
    refresh_token TEXT,
    expires_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (status <> 'CONNECTED' OR (access_token IS NOT NULL AND refresh_token IS NOT NULL AND expires_at IS NOT NULL))
);
CREATE TABLE app.youtube_authorization (
    user_id UUID PRIMARY KEY REFERENCES app.app_user(id) ON DELETE CASCADE,
    generation UUID NOT NULL,
    state_hash VARCHAR(64) NOT NULL UNIQUE,
    session_hash VARCHAR(64) NOT NULL,
    verifier TEXT NOT NULL,
    nonce VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE app.youtube_export (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app.app_user(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    source_playlist_id UUID NOT NULL,
    source_version BIGINT NOT NULL CHECK (source_version >= 0),
    name VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNCERTAIN', 'CANCELLED')),
    youtube_playlist_id VARCHAR(100),
    in_flight BOOLEAN NOT NULL DEFAULT FALSE,
    in_flight_position INTEGER CHECK (in_flight_position >= 0),
    error_code VARCHAR(60),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, request_id)
);
CREATE UNIQUE INDEX youtube_export_active_user_idx ON app.youtube_export(user_id)
    WHERE status IN ('QUEUED', 'RUNNING', 'FAILED', 'UNCERTAIN');
CREATE INDEX youtube_export_pending_idx ON app.youtube_export(created_at) WHERE status IN ('QUEUED', 'RUNNING');
CREATE TABLE app.youtube_export_item (
    export_id UUID NOT NULL REFERENCES app.youtube_export(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position >= 0),
    song_id UUID NOT NULL,
    youtube_id VARCHAR(11),
    status VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'COPIED', 'SKIPPED')),
    PRIMARY KEY(export_id, position),
    CHECK (status = 'SKIPPED' OR youtube_id IS NOT NULL)
);
-- Ciphertexts are readable only by the web role; neither collector nor operator receives access.
GRANT SELECT, INSERT, UPDATE, DELETE ON app.youtube_connection, app.youtube_authorization,
    app.youtube_export, app.youtube_export_item TO "${runtimeRole}";
