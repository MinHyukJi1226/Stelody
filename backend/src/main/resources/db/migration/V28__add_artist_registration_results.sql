-- Keep request keys after result expiry so delayed retransmission cannot create another artist.
CREATE TABLE app.artist_registration_request (
    id UUID PRIMARY KEY,
    actor_id UUID REFERENCES app.app_user(id) ON DELETE SET NULL,
    body_hash VARCHAR(64) NOT NULL CHECK (body_hash ~ '^[0-9a-f]{64}$'),
    status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING'
        CHECK (status IN ('PROCESSING','COMPLETED','FAILED','EXPIRED')),
    artist_id UUID,
    error_code VARCHAR(64),
    error_status SMALLINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp()+INTERVAL '24 hours',
    finished_at TIMESTAMPTZ,
    CHECK (expires_at > created_at),
    CHECK (
      (status='PROCESSING' AND artist_id IS NULL AND error_code IS NULL AND error_status IS NULL AND finished_at IS NULL)
      OR (status='COMPLETED' AND artist_id IS NOT NULL AND error_code IS NULL AND error_status IS NULL AND finished_at IS NOT NULL)
      OR (status='FAILED' AND artist_id IS NULL AND error_code IS NOT NULL AND error_status BETWEEN 400 AND 599 AND finished_at IS NOT NULL)
      OR (status='EXPIRED' AND artist_id IS NULL AND error_code IS NULL AND error_status IS NULL AND finished_at IS NULL)
    )
);
CREATE INDEX artist_registration_active_idx ON app.artist_registration_request(created_at)
    WHERE status='PROCESSING';
CREATE INDEX artist_registration_expiry_idx ON app.artist_registration_request(expires_at)
    WHERE status<>'EXPIRED';
GRANT SELECT, INSERT ON app.artist_registration_request TO "${runtimeRole}";
GRANT UPDATE(status,artist_id,error_code,error_status,finished_at)
    ON app.artist_registration_request TO "${runtimeRole}";
