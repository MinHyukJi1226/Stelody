CREATE TABLE app.catalog_audit (
    id UUID PRIMARY KEY,
    target_type VARCHAR(24) NOT NULL,
    target_id UUID NOT NULL,
    actor_id UUID REFERENCES app.app_user(id) ON DELETE SET NULL,
    database_actor TEXT NOT NULL DEFAULT CURRENT_USER,
    action VARCHAR(24) NOT NULL,
    before_value JSONB,
    after_value JSONB NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (btrim(reason) <> ''),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX catalog_audit_target_idx ON app.catalog_audit(target_type,target_id,changed_at DESC,id);
GRANT SELECT, INSERT ON app.catalog_audit TO "${runtimeRole}";

-- Role assignment is reserved for a separately provisioned, non-web operator connection.
REVOKE INSERT ON app.app_user FROM "${runtimeRole}";
GRANT INSERT (id,google_subject,email) ON app.app_user TO "${runtimeRole}";
REVOKE UPDATE ON app.app_user FROM "${runtimeRole}";
GRANT UPDATE (email,last_login_at) ON app.app_user TO "${runtimeRole}";
