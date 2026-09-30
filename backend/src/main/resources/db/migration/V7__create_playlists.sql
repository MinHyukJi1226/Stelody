CREATE TABLE app.playlist (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app.app_user(id) ON DELETE CASCADE,
    name VARCHAR(50) NOT NULL CHECK (btrim(name) <> ''),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);
CREATE INDEX playlist_user_created_idx ON app.playlist(user_id, created_at DESC, id DESC);
CREATE TABLE app.playlist_item (
    id UUID PRIMARY KEY,
    playlist_id UUID NOT NULL REFERENCES app.playlist(id) ON DELETE CASCADE,
    song_id UUID NOT NULL REFERENCES app.song_entry(id),
    position INTEGER NOT NULL CHECK (position >= 0),
    added_at TIMESTAMPTZ NOT NULL,
    UNIQUE (playlist_id, song_id),
    -- A permutation can temporarily share positions inside a transaction, never after commit.
    CONSTRAINT playlist_item_position_unique UNIQUE (playlist_id, position) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX playlist_item_song_idx ON app.playlist_item(song_id);
GRANT SELECT, INSERT, UPDATE, DELETE ON app.playlist, app.playlist_item TO "${runtimeRole}";
