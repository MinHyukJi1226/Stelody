-- Apply as schema owner after V16, before enabling discovery classification.
GRANT SELECT ON app.collection_rule TO stelody_collector;
