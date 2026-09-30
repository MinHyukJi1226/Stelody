package com.stelody.favorite.repository;

import com.stelody.catalog.repository.PublicCatalogSql;
import com.stelody.favorite.domain.FavoriteCursor.Position;
import com.stelody.favorite.web.FavoriteException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FavoriteQueries {
  public record Saved(UUID songId, Instant savedAt) {}

  public record Counts(long totalCount, long availableCount) {}

  private final JdbcClient jdbc;

  public FavoriteQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // Both saves and deletes lock the same account row, including when the list is empty.
  // The lock is held through JPA's commit so concurrent saves cannot exceed the per-user limit.
  public void lockActiveAccount(UUID userId) {
    var active =
        jdbc.sql("SELECT status FROM app.app_user WHERE id = :userId FOR UPDATE")
            .param("userId", userId)
            .query(String.class)
            .optional();
    if (active.isEmpty() || !active.get().equals("ACTIVE"))
      throw new FavoriteException(401, "SESSION_EXPIRED", "다시 로그인해 주세요");
  }

  public boolean publiclyAvailable(UUID songId) {
    return jdbc.sql(
            PublicCatalogSql.SONGS
                + " SELECT EXISTS (SELECT 1 FROM public_songs WHERE id = :songId)")
        .param("songId", songId)
        .query(Boolean.class)
        .single();
  }

  public Counts counts(UUID userId) {
    return jdbc.sql(
            PublicCatalogSql.SONGS
                + """
         SELECT count(*) AS total_count, count(s.id) AS available_count
         FROM app.favorite f LEFT JOIN public_songs s ON s.id = f.song_id WHERE f.user_id = :userId
        """)
        .param("userId", userId)
        .query((rs, n) -> new Counts(rs.getLong("total_count"), rs.getLong("available_count")))
        .single();
  }

  public List<Saved> list(UUID userId, int size, Position cursor) {
    var sql =
        new StringBuilder("SELECT song_id, created_at FROM app.favorite WHERE user_id = :userId");
    if (cursor != null) sql.append(" AND (created_at, song_id) < (:savedAt, :songId)");
    sql.append(" ORDER BY created_at DESC, song_id DESC LIMIT :limit");
    var statement = jdbc.sql(sql.toString()).param("userId", userId).param("limit", size + 1);
    if (cursor != null)
      statement.param("savedAt", Timestamp.from(cursor.savedAt())).param("songId", cursor.songId());
    return statement
        .query(
            (rs, n) ->
                new Saved(
                    rs.getObject("song_id", UUID.class), rs.getTimestamp("created_at").toInstant()))
        .list();
  }
}
