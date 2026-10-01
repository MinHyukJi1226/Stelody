package com.stelody.catalog.repository;

public final class PublicCatalogSql {
  private PublicCatalogSql() {}

  // Shared by cards, details, related songs, member counts and member song lists.
  public static final String SONGS =
      """
      WITH public_songs AS (
        SELECT s.*, v.youtube_id, v.video_kind, COALESCE(v.published_at, v.source_published_at) AS published_at,
          COALESCE(v.thumbnail_url, v.source_thumbnail_url) AS thumbnail_url, v.embeddable,
          (SELECT count(*) FROM app.song_member sm WHERE sm.song_id = s.id AND sm.confirmed)
          + (SELECT count(*) FROM app.song_external_artist ea WHERE ea.song_id = s.id AND ea.confirmed)
            AS participant_count
        FROM app.song_entry s JOIN app.video v ON v.id = s.representative_video_id AND v.song_id = s.id
        WHERE s.visibility = 'PUBLISHED' AND v.availability = 'PUBLIC' AND COALESCE(v.published_at, v.source_published_at) IS NOT NULL
          AND EXISTS (SELECT 1 FROM app.song_member sm WHERE sm.song_id = s.id AND sm.confirmed)
      )
      """;
}
