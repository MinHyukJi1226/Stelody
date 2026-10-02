-- Provision stelody_operator with a private password and CONNECT separately, then run as schema owner after V11.
-- This account must not be used by the web server or the collector.
GRANT USAGE ON SCHEMA app TO stelody_operator;
GRANT SELECT (id,role,status), UPDATE (role) ON app.app_user TO stelody_operator;
GRANT INSERT ON app.catalog_audit TO stelody_operator;
