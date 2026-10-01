CREATE TABLE app.admin_audit (
    id UUID PRIMARY KEY,
    review_id UUID NOT NULL REFERENCES app.review_item(id),
    actor_id UUID REFERENCES app.app_user(id) ON DELETE SET NULL,
    before_status VARCHAR(16) NOT NULL,
    after_status VARCHAR(16) NOT NULL,
    before_note VARCHAR(500),
    after_note VARCHAR(500) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX admin_audit_review_idx ON app.admin_audit(review_id,changed_at DESC);
GRANT UPDATE (review_status,review_note,reviewed_at,version) ON app.review_item TO "${runtimeRole}";
GRANT INSERT ON app.admin_audit TO "${runtimeRole}";
