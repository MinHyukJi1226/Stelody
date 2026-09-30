package com.stelody.song.repository;

import com.stelody.song.domain.SongCursor.Position;
import com.stelody.song.domain.SongQuery;
import com.stelody.song.dto.SongDtos;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SongRepository {
  public record Row(
      UUID id,
      String title,
      String type,
      UUID workId,
      UUID videoId,
      String youtubeId,
      String videoKind,
      Instant publishedAt,
      String thumbnailUrl,
      boolean embeddable,
      boolean collaboration,
      boolean specialEvent,
      String specialEventLabel,
      Long viewCount,
      Instant observedAt,
      String searchVisibility,
      String recommendedQuery,
      Instant checkedAt,
      int rank) {}

  private final JdbcClient jdbc;

  public SongRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public UUID publication() {
    return jdbc.sql(SongSqlQueries.PUBLICATION)
        .query((rs, n) -> new Publication(rs.getObject(1, UUID.class)))
        .single()
        .id();
  }

  private record Publication(UUID id) {}

  public List<Row> list(SongQuery query, UUID publication, Position cursor) {
    var statement = SongSqlQueries.list(query, publication, cursor);
    return jdbc.sql(statement.sql()).params(statement.parameters()).query(this::map).list();
  }

  public Optional<Row> find(UUID id, UUID publication) {
    var statement = SongSqlQueries.find(id, publication);
    return jdbc.sql(statement.sql()).params(statement.parameters()).query(this::map).optional();
  }

  public List<Row> findAll(List<UUID> ids, UUID publication) {
    if (ids.isEmpty()) return List.of();
    var statement = SongSqlQueries.findAll(ids, publication);
    return jdbc.sql(statement.sql()).params(statement.parameters()).query(this::map).list();
  }

  public List<Row> related(Row song, UUID publication) {
    var statement = SongSqlQueries.related(song.id(), song.workId(), publication);
    return jdbc.sql(statement.sql()).params(statement.parameters()).query(this::map).list();
  }

  // Batch the relationships once per page, rather than one query per card.
  public List<SongDtos.Card> cards(List<Row> rows) {
    if (rows.isEmpty()) return List.of();
    var ids = rows.stream().map(Row::id).toList();
    var participants = loadParticipants(ids);
    var workIds =
        rows.stream().map(Row::workId).filter(java.util.Objects::nonNull).distinct().toList();
    var works = loadWorks(workIds);
    return rows.stream()
        .map(
            row ->
                new SongDtos.Card(
                    row.id(),
                    row.title(),
                    row.type(),
                    row.publishedAt(),
                    row.thumbnailUrl(),
                    List.copyOf(participants.getOrDefault(row.id(), List.of())),
                    works.get(row.workId()),
                    row.collaboration(),
                    row.specialEvent(),
                    row.specialEventLabel(),
                    row.viewCount(),
                    row.observedAt()))
        .toList();
  }

  private Map<UUID, List<SongDtos.Participant>> loadParticipants(List<UUID> ids) {
    var participants = new HashMap<UUID, List<SongDtos.Participant>>();
    jdbc.sql(SongSqlQueries.PARTICIPANTS)
        .param("ids", ids)
        .query(
            (rs, n) -> {
              participants
                  .computeIfAbsent(rs.getObject("song_id", UUID.class), k -> new ArrayList<>())
                  .add(
                      new SongDtos.Participant(
                          rs.getObject("id", UUID.class),
                          rs.getString("name"),
                          rs.getString("kind"),
                          rs.getString("activity_status")));
              return 0;
            })
        .list();
    return participants;
  }

  private Map<UUID, SongDtos.Work> loadWorks(List<UUID> workIds) {
    var works = new HashMap<UUID, SongDtos.Work>();
    if (!workIds.isEmpty())
      jdbc.sql(SongSqlQueries.WORKS)
          .param("ids", workIds)
          .query(
              (rs, n) -> {
                var work =
                    works.computeIfAbsent(
                        rs.getObject("id", UUID.class),
                        id -> {
                          try {
                            return new SongDtos.Work(id, rs.getString("title"), new ArrayList<>());
                          } catch (SQLException exception) {
                            throw new IllegalStateException(exception);
                          }
                        });
                UUID artistId = rs.getObject("artist_id", UUID.class);
                if (artistId != null)
                  work.artists().add(new SongDtos.Artist(artistId, rs.getString("artist_name")));
                return 0;
              })
          .list();
    return works;
  }

  private Row map(ResultSet rs, int n) throws SQLException {
    return new Row(
        rs.getObject("id", UUID.class),
        rs.getString("title"),
        rs.getString("song_type"),
        rs.getObject("work_id", UUID.class),
        rs.getObject("representative_video_id", UUID.class),
        rs.getString("youtube_id"),
        rs.getString("video_kind"),
        instant(rs, "published_at"),
        rs.getString("thumbnail_url"),
        rs.getBoolean("embeddable"),
        rs.getLong("participant_count") > 1,
        rs.getBoolean("is_special_event"),
        rs.getString("special_event_label"),
        rs.getObject("view_count", Long.class),
        instant(rs, "observed_at"),
        rs.getString("search_visibility"),
        rs.getString("recommended_search_query"),
        instant(rs, "search_checked_at"),
        rs.getInt("search_rank"));
  }

  private Instant instant(ResultSet rs, String column) throws SQLException {
    var value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
