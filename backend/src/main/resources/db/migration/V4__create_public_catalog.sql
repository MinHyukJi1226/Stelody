CREATE TABLE app.member (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL CHECK (btrim(name) <> ''),
    search_name VARCHAR(200) NOT NULL,
    generation SMALLINT CHECK (generation > 0),
    activity_status VARCHAR(16) NOT NULL CHECK (activity_status IN ('ACTIVE', 'GRADUATED')),
    profile_image_url TEXT,
    debut_date DATE,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE app.member_alias (
    member_id UUID NOT NULL REFERENCES app.member(id),
    alias VARCHAR(200) NOT NULL,
    search_alias VARCHAR(300) NOT NULL,
    PRIMARY KEY (member_id, search_alias)
);
CREATE TABLE app.channel (
    id UUID PRIMARY KEY,
    youtube_id VARCHAR(24) NOT NULL UNIQUE CHECK (youtube_id ~ '^UC[A-Za-z0-9_-]{22}$'),
    name VARCHAR(200) NOT NULL,
    member_id UUID REFERENCES app.member(id),
    channel_type VARCHAR(16) NOT NULL CHECK (channel_type IN ('GROUP', 'MEMBER', 'EXTERNAL')),
    collection_enabled BOOLEAN NOT NULL DEFAULT false
);
CREATE TABLE app.artist (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL CHECK (btrim(name) <> ''),
    search_name VARCHAR(300) NOT NULL
);
CREATE TABLE app.artist_alias (
    artist_id UUID NOT NULL REFERENCES app.artist(id),
    alias VARCHAR(200) NOT NULL,
    search_alias VARCHAR(300) NOT NULL,
    PRIMARY KEY (artist_id, search_alias)
);
CREATE TABLE app.musical_work (
    id UUID PRIMARY KEY,
    title VARCHAR(300) NOT NULL CHECK (btrim(title) <> ''),
    search_title VARCHAR(400) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE app.work_alias (
    work_id UUID NOT NULL REFERENCES app.musical_work(id),
    alias VARCHAR(300) NOT NULL,
    search_alias VARCHAR(400) NOT NULL,
    PRIMARY KEY (work_id, search_alias)
);
CREATE TABLE app.work_artist (
    work_id UUID NOT NULL REFERENCES app.musical_work(id),
    artist_id UUID NOT NULL REFERENCES app.artist(id),
    position INTEGER NOT NULL CHECK (position >= 0),
    PRIMARY KEY (work_id, artist_id),
    UNIQUE (work_id, position)
);
CREATE TABLE app.song_entry (
    id UUID PRIMARY KEY,
    title VARCHAR(300) NOT NULL CHECK (btrim(title) <> ''),
    search_title VARCHAR(400) NOT NULL,
    song_type VARCHAR(16) NOT NULL CHECK (song_type IN ('ORIGINAL', 'COVER')),
    work_id UUID REFERENCES app.musical_work(id),
    visibility VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK (visibility IN ('DRAFT', 'PUBLISHED', 'HIDDEN')),
    representative_video_id UUID,
    is_special_event BOOLEAN NOT NULL DEFAULT false,
    special_event_label VARCHAR(60),
    search_visibility VARCHAR(16) NOT NULL DEFAULT 'UNCHECKED' CHECK (search_visibility IN ('UNCHECKED', 'NORMAL', 'DIFFICULT')),
    recommended_search_query VARCHAR(300),
    search_checked_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK ((is_special_event AND special_event_label IS NOT NULL AND btrim(special_event_label) <> '')
        OR (NOT is_special_event AND special_event_label IS NULL))
);
CREATE TABLE app.song_alias (
    song_id UUID NOT NULL REFERENCES app.song_entry(id),
    alias VARCHAR(300) NOT NULL,
    search_alias VARCHAR(400) NOT NULL,
    PRIMARY KEY (song_id, search_alias)
);
CREATE TABLE app.song_member (
    song_id UUID NOT NULL REFERENCES app.song_entry(id),
    member_id UUID NOT NULL REFERENCES app.member(id),
    position INTEGER NOT NULL CHECK (position >= 0),
    confirmed BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY (song_id, member_id),
    UNIQUE (song_id, position)
);
CREATE INDEX song_member_member_idx ON app.song_member(member_id, song_id) WHERE confirmed;
CREATE TABLE app.song_external_artist (
    song_id UUID NOT NULL REFERENCES app.song_entry(id),
    artist_id UUID NOT NULL REFERENCES app.artist(id),
    position INTEGER NOT NULL CHECK (position >= 0),
    confirmed BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY (song_id, artist_id),
    UNIQUE (song_id, position)
);
CREATE TABLE app.video (
    id UUID PRIMARY KEY,
    song_id UUID NOT NULL REFERENCES app.song_entry(id),
    youtube_id VARCHAR(11) NOT NULL UNIQUE CHECK (youtube_id ~ '^[A-Za-z0-9_-]{11}$'),
    channel_id UUID REFERENCES app.channel(id),
    video_kind VARCHAR(24) NOT NULL CHECK (video_kind IN ('OFFICIAL_MV', 'OFFICIAL_COVER', 'AUDIO', 'REUPLOAD', 'OTHER')),
    availability VARCHAR(16) NOT NULL CHECK (availability IN ('PUBLIC', 'PRIVATE', 'DELETED', 'UNAVAILABLE', 'UNLISTED')),
    source_title VARCHAR(500) NOT NULL,
    published_at TIMESTAMPTZ,
    thumbnail_url TEXT,
    embeddable BOOLEAN NOT NULL DEFAULT true,
    UNIQUE (id, song_id)
);
-- A representative upload must belong to this song, including when it is replaced later.
ALTER TABLE app.song_entry ADD CONSTRAINT song_representative_video_fk
    FOREIGN KEY (representative_video_id, id) REFERENCES app.video(id, song_id);
CREATE INDEX video_song_idx ON app.video(song_id);
CREATE INDEX video_public_date_idx ON app.video(published_at DESC, id) WHERE availability = 'PUBLIC';
CREATE INDEX song_work_idx ON app.song_entry(work_id);
CREATE INDEX song_search_title_idx ON app.song_entry(search_title);

-- Collectors will publish a completed generation by switching this pointer in one transaction.
CREATE TABLE app.view_publication (
    id UUID PRIMARY KEY,
    published_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE app.published_video_view (
    publication_id UUID NOT NULL REFERENCES app.view_publication(id),
    video_id UUID NOT NULL REFERENCES app.video(id),
    view_count BIGINT NOT NULL CHECK (view_count >= 0),
    observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (publication_id, video_id)
);
CREATE TABLE app.catalog_state (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    view_publication_id UUID REFERENCES app.view_publication(id)
);
INSERT INTO app.catalog_state (singleton) VALUES (true);
GRANT SELECT ON app.member, app.member_alias, app.channel, app.artist, app.artist_alias,
    app.musical_work, app.work_alias, app.work_artist, app.song_entry, app.song_alias,
    app.song_member, app.song_external_artist, app.video, app.view_publication,
    app.published_video_view, app.catalog_state TO "${runtimeRole}";
