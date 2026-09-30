package com.stelody.playlist.repository;

import com.stelody.catalog.repository.PublicCatalogSql;

final class PlaylistSqlQueries {
  private PlaylistSqlQueries() {}

  static final String SUMMARY =
      PublicCatalogSql.SONGS
          + """
      SELECT p.id, p.name, p.version, p.created_at, p.updated_at,
        count(i.id) AS total_count, count(s.id) AS available_count
      FROM app.playlist p LEFT JOIN app.playlist_item i ON i.playlist_id = p.id
        LEFT JOIN public_songs s ON s.id = i.song_id
      WHERE p.user_id = :userId
      """;
  static final String LOCK_ACCOUNT =
      "SELECT status FROM app.app_user WHERE id = :userId FOR UPDATE";
}
