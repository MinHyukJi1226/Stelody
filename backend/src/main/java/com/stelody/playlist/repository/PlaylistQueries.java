package com.stelody.playlist.repository;

import com.stelody.playlist.domain.PlaylistCursor.ItemPosition;
import com.stelody.playlist.domain.PlaylistCursor.ListPosition;
import com.stelody.playlist.dto.PlaylistDtos;
import com.stelody.playlist.web.PlaylistException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PlaylistQueries {
  public record ItemRow(UUID id, UUID songId, int position, Instant addedAt) {}

  private final JdbcClient jdbc;

  public PlaylistQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void lockActiveAccount(UUID userId) {
    var active =
        jdbc.sql(PlaylistSqlQueries.LOCK_ACCOUNT)
            .param("userId", userId)
            .query(String.class)
            .optional();
    if (active.isEmpty() || !active.get().equals("ACTIVE"))
      throw new PlaylistException(401, "SESSION_EXPIRED", "다시 로그인해 주세요");
  }

  public boolean publiclyAvailable(UUID songId) {
    return jdbc.sql(PlaylistSqlQueries.PUBLIC_SONG)
        .param("songId", songId)
        .query(Boolean.class)
        .single();
  }

  public PlaylistDtos.Summary summary(UUID userId, UUID id) {
    return jdbc.sql(PlaylistSqlQueries.SUMMARY + " AND p.id = :id GROUP BY p.id")
        .param("userId", userId)
        .param("id", id)
        .query(this::summary)
        .optional()
        .orElseThrow(PlaylistException::missing);
  }

  public List<PlaylistDtos.Summary> list(UUID userId, int size, ListPosition cursor) {
    String boundary = cursor == null ? "" : " AND (p.created_at, p.id) < (:createdAt, :id)";
    var statement =
        jdbc.sql(
                PlaylistSqlQueries.SUMMARY
                    + boundary
                    + " GROUP BY p.id ORDER BY p.created_at DESC, p.id DESC LIMIT :limit")
            .param("userId", userId)
            .param("limit", size + 1);
    if (cursor != null)
      statement.param("createdAt", Timestamp.from(cursor.createdAt())).param("id", cursor.id());
    return statement.query(this::summary).list();
  }

  public List<ItemRow> items(UUID userId, UUID playlistId, int size, ItemPosition cursor) {
    var statement =
        jdbc.sql(
                PlaylistSqlQueries.ITEMS
                    + (cursor == null ? "" : " AND i.position > :position")
                    + " ORDER BY i.position LIMIT :limit")
            .param("userId", userId)
            .param("playlistId", playlistId)
            .param("limit", size + 1);
    if (cursor != null) statement.param("position", cursor.position());
    return statement
        .query(
            (rs, n) ->
                new ItemRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("song_id", UUID.class),
                    rs.getInt("position"),
                    rs.getTimestamp("added_at").toInstant()))
        .list();
  }

  public void closeGap(UUID userId, UUID playlistId, int position) {
    jdbc.sql(PlaylistSqlQueries.CLOSE_GAP)
        .param("userId", userId)
        .param("playlistId", playlistId)
        .param("position", position)
        .update();
  }

  private PlaylistDtos.Summary summary(ResultSet rs, int row) throws SQLException {
    return new PlaylistDtos.Summary(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getLong("version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getLong("total_count"),
        rs.getLong("available_count"));
  }
}
