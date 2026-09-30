CREATE TABLE app.favorite (
    user_id UUID NOT NULL REFERENCES app.app_user(id) ON DELETE CASCADE,
    song_id UUID NOT NULL REFERENCES app.song_entry(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, song_id)
);
CREATE INDEX favorite_user_saved_idx ON app.favorite(user_id, created_at DESC, song_id DESC);
CREATE INDEX favorite_song_idx ON app.favorite(song_id);
GRANT SELECT, INSERT, DELETE ON app.favorite TO "${runtimeRole}";
