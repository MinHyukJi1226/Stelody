-- Apply as schema owner after V15; collector login is provisioned separately.
GRANT USAGE ON SCHEMA app TO stelody_collector;
GRANT SELECT, UPDATE, DELETE ON app.collection_retry_request TO stelody_collector;
-- Request reasons and administrator identity remain in catalog_audit, inaccessible here.
