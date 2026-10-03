-- Apply as schema owner after V19, before allowing cover auto publication.
GRANT SELECT ON app.cover_publication_control,app.member,app.member_alias TO stelody_collector;
GRANT EXECUTE ON FUNCTION app.publish_discovered_cover(UUID,TIMESTAMPTZ,UUID,BIGINT,TEXT,BOOLEAN,TEXT,UUID) TO stelody_collector;
