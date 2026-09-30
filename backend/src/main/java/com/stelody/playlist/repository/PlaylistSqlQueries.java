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
  static final String ITEMS =
      """
      SELECT i.id, i.song_id, i.position, i.added_at FROM app.playlist_item i
        JOIN app.playlist p ON p.id = i.playlist_id
      WHERE p.user_id = :userId AND p.id = :playlistId
      """;
  static final String LOCK_ACCOUNT =
      "SELECT status FROM app.app_user WHERE id = :userId FOR UPDATE";
  static final String PUBLIC_SONG =
      PublicCatalogSql.SONGS + " SELECT EXISTS (SELECT 1 FROM public_songs WHERE id = :songId)";
  static final String ORDER =
      """
      UPDATE app.playlist_item i SET position = requested.ordinality - 1
        FROM unnest(CAST(:itemIds AS uuid[])) WITH ORDINALITY AS requested(id, ordinality)
      WHERE i.id = requested.id AND i.playlist_id = :playlistId
        AND EXISTS (SELECT 1 FROM app.playlist p WHERE p.id = i.playlist_id AND p.user_id = :userId)
      """;
  static final String CLOSE_GAP =
      """
      UPDATE app.playlist_item i SET position = position - 1
      WHERE i.playlist_id = :playlistId AND i.position > :position
        AND EXISTS (SELECT 1 FROM app.playlist p WHERE p.id = i.playlist_id AND p.user_id = :userId)
      """;
}
