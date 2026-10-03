CREATE TABLE app.account_reauthentication (
    user_id UUID NOT NULL REFERENCES app.app_user(id) ON DELETE CASCADE,
    session_hash VARCHAR(64) NOT NULL,
    generation UUID NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','VERIFYING','CONFIRMED')),
    state_hash VARCHAR(64) UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id,session_hash),
    CHECK ((status='PENDING') = (state_hash IS NOT NULL))
);
CREATE INDEX account_reauthentication_expiry_idx ON app.account_reauthentication(expires_at);
GRANT SELECT, INSERT, UPDATE, DELETE ON app.account_reauthentication TO "${runtimeRole}";

-- Only this narrow operation can delete accounts or anonymize otherwise immutable audits.
-- The application supplies the authenticated UUID and its server-side session hash.
CREATE FUNCTION app.withdraw_account(account_id UUID, current_session_hash TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    IF NOT pg_try_advisory_xact_lock(2026100301::bigint) THEN
        RETURN FALSE;
    END IF;
    PERFORM id FROM app.app_user WHERE id=account_id AND status='ACTIVE' FOR UPDATE;
    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;
    PERFORM user_id FROM app.account_reauthentication
        WHERE user_id=account_id AND session_hash=current_session_hash
          AND status='CONFIRMED' AND expires_at>clock_timestamp() FOR UPDATE;
    IF NOT FOUND OR EXISTS (
        SELECT 1 FROM app.youtube_connection WHERE user_id=account_id
          AND (status<>'DISCONNECTED' OR access_token IS NOT NULL OR refresh_token IS NOT NULL)
    ) THEN
        RETURN FALSE;
    END IF;
    DELETE FROM session.spring_session WHERE principal_name=account_id::text;
    UPDATE app.catalog_audit SET target_id=gen_random_uuid()
        WHERE target_type='ACCOUNT_ROLE' AND target_id=account_id;
    -- ON DELETE SET NULL anonymizes actor IDs; CASCADE removes all private lists and tokens.
    DELETE FROM app.app_user WHERE id=account_id;
    RETURN TRUE;
END;
$$;
REVOKE ALL ON FUNCTION app.withdraw_account(UUID,TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.withdraw_account(UUID,TEXT) TO "${runtimeRole}";
