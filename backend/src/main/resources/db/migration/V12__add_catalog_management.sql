ALTER TABLE app.member ADD COLUMN edited_at TIMESTAMPTZ,
    ADD COLUMN birthday_month SMALLINT,
    ADD COLUMN birthday_day SMALLINT,
    ADD CONSTRAINT member_birthday_check CHECK (
      (birthday_month IS NULL AND birthday_day IS NULL) OR
      (birthday_month IS NOT NULL AND birthday_day IS NOT NULL AND birthday_month BETWEEN 1 AND 12 AND birthday_day BETWEEN 1 AND
        CASE birthday_month WHEN 2 THEN 29 WHEN 4 THEN 30 WHEN 6 THEN 30 WHEN 9 THEN 30 WHEN 11 THEN 30 ELSE 31 END));
ALTER TABLE app.artist ADD COLUMN version BIGINT NOT NULL DEFAULT 0, ADD COLUMN edited_at TIMESTAMPTZ;
ALTER TABLE app.musical_work ADD COLUMN edited_at TIMESTAMPTZ;
ALTER TABLE app.song_entry ADD COLUMN edited_at TIMESTAMPTZ;
ALTER TABLE app.channel ADD COLUMN version BIGINT NOT NULL DEFAULT 0, ADD COLUMN edited_at TIMESTAMPTZ;
ALTER TABLE app.video ADD COLUMN version BIGINT NOT NULL DEFAULT 0, ADD COLUMN edited_at TIMESTAMPTZ;

ALTER TABLE app.review_item DROP CONSTRAINT review_item_review_status_check;
ALTER TABLE app.review_item ADD CONSTRAINT review_item_review_status_check
    CHECK (review_status IN ('PENDING','IGNORED','REGISTERED'));
ALTER TABLE app.review_item ADD COLUMN registered_video_id UUID REFERENCES app.video(id),
    ADD CONSTRAINT review_registration_check CHECK ((review_status='REGISTERED') = (registered_video_id IS NOT NULL));
GRANT UPDATE (registered_video_id) ON app.review_item TO "${runtimeRole}";

GRANT INSERT, UPDATE ON app.member, app.artist, app.musical_work, app.song_entry, app.channel TO "${runtimeRole}";
GRANT INSERT, DELETE ON app.member_alias, app.artist_alias, app.work_alias, app.work_artist,
    app.song_alias, app.song_member, app.song_external_artist TO "${runtimeRole}";
-- Initial observed values can be inserted by registration; later source updates remain collector-owned.
GRANT INSERT ON app.video TO "${runtimeRole}";
GRANT UPDATE (video_kind,published_at,thumbnail_url,version,edited_at) ON app.video TO "${runtimeRole}";

CREATE TABLE app.external_link (
    id UUID PRIMARY KEY,
    song_id UUID REFERENCES app.song_entry(id),
    work_id UUID REFERENCES app.musical_work(id),
    platform VARCHAR(60) NOT NULL CHECK (btrim(platform) <> ''),
    url TEXT NOT NULL,
    source_url TEXT NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL,
    CHECK ((song_id IS NULL) <> (work_id IS NULL))
);
CREATE INDEX external_link_song_idx ON app.external_link(song_id);
CREATE INDEX external_link_work_idx ON app.external_link(work_id);
CREATE TABLE app.karaoke_entry (
    id UUID PRIMARY KEY,
    song_id UUID REFERENCES app.song_entry(id),
    work_id UUID REFERENCES app.musical_work(id),
    provider VARCHAR(2) NOT NULL CHECK (provider IN ('TJ','KY')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('UNKNOWN','NOT_LISTED','REGISTERED')),
    number VARCHAR(40),
    source_url TEXT,
    checked_at TIMESTAMPTZ,
    CHECK ((song_id IS NULL) <> (work_id IS NULL)),
    CHECK ((status='REGISTERED' AND number IS NOT NULL AND btrim(number) <> '' AND source_url IS NOT NULL AND checked_at IS NOT NULL)
      OR (status<>'REGISTERED' AND number IS NULL)),
    UNIQUE (song_id,provider), UNIQUE (work_id,provider)
);
GRANT SELECT, INSERT, DELETE ON app.external_link, app.karaoke_entry TO "${runtimeRole}";
