-- Apply as schema owner after V27, before allowing cover auto publication.
GRANT SELECT ON app.cover_publication_control,app.member,app.member_alias TO stelody_collector;
GRANT EXECUTE ON FUNCTION app.publish_discovered_cover(UUID,TIMESTAMPTZ,UUID,BIGINT,TEXT,BOOLEAN,TEXT,UUID) TO stelody_collector;
-- Requires V27. The function checks the collection owner token and successful run scope.
GRANT EXECUTE ON FUNCTION app.cleanup_discovery_reviews(UUID,UUID) TO stelody_collector;
