-- Query-only receipts survive account/session deletion, but never survive backup restoration.
CREATE TABLE app.withdrawal_result (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES app.app_user(id) ON DELETE SET NULL,
    token_hash VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PREPARED','PROCESSING','COMPLETED','NOT_COMPLETED','UNKNOWN')),
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()+INTERVAL '24 hours',
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CHECK ((status='COMPLETED') = (completed_at IS NOT NULL))
);
CREATE INDEX withdrawal_result_expiry_idx ON app.withdrawal_result(expires_at);
GRANT SELECT, INSERT, UPDATE, DELETE ON app.withdrawal_result TO "${runtimeRole}";
