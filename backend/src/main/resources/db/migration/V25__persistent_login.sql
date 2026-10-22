CREATE TABLE app.persistent_login (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app.app_user(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    previous_token_hash VARCHAR(64),
    previous_valid_until TIMESTAMPTZ,
    csrf_token VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at>created_at AND expires_at<=created_at+INTERVAL '30 days'),
    CHECK ((previous_token_hash IS NULL)=(previous_valid_until IS NULL))
);
CREATE INDEX persistent_login_user_idx ON app.persistent_login(user_id);
CREATE INDEX persistent_login_expiry_idx ON app.persistent_login(expires_at);
REVOKE ALL ON app.persistent_login FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE,DELETE ON app.persistent_login TO "${runtimeRole}";

-- Status or role changes must not let an old device credential become valid again later.
CREATE FUNCTION app.revoke_persistent_login_on_account_change() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    DELETE FROM app.persistent_login WHERE user_id=NEW.id;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION app.revoke_persistent_login_on_account_change() FROM PUBLIC;
CREATE TRIGGER revoke_persistent_login_on_account_change
    AFTER UPDATE OF role,status ON app.app_user FOR EACH ROW
    WHEN (OLD.role IS DISTINCT FROM NEW.role OR OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION app.revoke_persistent_login_on_account_change();
