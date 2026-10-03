CREATE TABLE app.collection_retry_request (
    id UUID PRIMARY KEY,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('VIDEO','DISCOVERY')),
    run_id UUID NOT NULL,
    expected_attempt INTEGER NOT NULL CHECK (expected_attempt > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    execution_run_id UUID,
    lease_token UUID,
    error_code VARCHAR(64)
);
-- Bound administrator requests globally; the collector processes one per invocation.
CREATE UNIQUE INDEX collection_retry_active_idx ON app.collection_retry_request((true))
    WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX collection_retry_created_idx ON app.collection_retry_request(created_at DESC,id);
GRANT SELECT ON app.collection_retry_request, app.collection_target TO "${runtimeRole}";
GRANT INSERT (id,kind,run_id,expected_attempt) ON app.collection_retry_request TO "${runtimeRole}";
