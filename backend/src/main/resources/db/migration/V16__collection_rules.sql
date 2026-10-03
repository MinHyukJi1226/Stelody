ALTER TABLE app.discovery_run ADD COLUMN rule_version VARCHAR(60);

CREATE TABLE app.collection_rule (
    id UUID PRIMARY KEY CHECK (id = '00000000-0000-0000-0000-000000000001'),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    configuration JSONB NOT NULL CHECK (jsonb_typeof(configuration) = 'object'),
    edited_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO app.collection_rule(id, configuration) VALUES (
    '00000000-0000-0000-0000-000000000001',
    '{"cover":[{"text":"cover","match":"WORD"},{"text":"covered by","match":"WORD"},{"text":"歌ってみた","match":"PHRASE"}],"original":[{"text":"original","match":"WORD"},{"text":"mv","match":"WORD"},{"text":"오리지널","match":"PHRASE"}],"exclude":[{"text":"shorts","match":"WORD"},{"text":"clip","match":"WORD"},{"text":"clips","match":"WORD"},{"text":"livestream","match":"WORD"},{"text":"[클립]","match":"PHRASE"},{"text":"[방송]","match":"PHRASE"},{"text":"[다시보기]","match":"PHRASE"}]}'
);
GRANT SELECT ON app.collection_rule TO "${runtimeRole}";
GRANT UPDATE (configuration, version, edited_at) ON app.collection_rule TO "${runtimeRole}";
