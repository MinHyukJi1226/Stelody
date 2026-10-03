-- Run as the schema owner after V13, in addition to collector-grants.sql.
GRANT UPDATE (view_collection_started_at) ON app.video TO stelody_collector;
