package com.stelody.song.repository;

import com.stelody.catalog.domain.SearchText;
import com.stelody.catalog.repository.PublicCatalogSql;
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
    return jdbc.sql("SELECT view_publication_id FROM app.catalog_state WHERE singleton")
        .query((rs, n) -> new Publication(rs.getObject(1, UUID.class)))
        .single()
        .id();
  }

  private record Publication(UUID id) {}

  private static final String RANK =
      """
      CASE WHEN :q = '' THEN 3
        WHEN s.search_title = :q
          OR EXISTS (SELECT 1 FROM app.song_alias a WHERE a.song_id = s.id AND a.search_alias = :q)
          OR EXISTS (SELECT 1 FROM app.musical_work w WHERE w.id = s.work_id AND w.search_title = :q)
          OR EXISTS (SELECT 1 FROM app.work_alias a WHERE a.work_id = s.work_id AND a.search_alias = :q) THEN 1
        WHEN s.search_title LIKE :pattern ESCAPE '!'
          OR EXISTS (SELECT 1 FROM app.song_alias a WHERE a.song_id = s.id AND a.search_alias LIKE :pattern ESCAPE '!')
          OR EXISTS (SELECT 1 FROM app.musical_work w WHERE w.id = s.work_id AND w.search_title LIKE :pattern ESCAPE '!')
          OR EXISTS (SELECT 1 FROM app.work_alias a WHERE a.work_id = s.work_id AND a.search_alias LIKE :pattern ESCAPE '!') THEN 2
        ELSE 3 END AS search_rank
      """;
  private static final String PARTICIPANT_MATCH =
      """
      EXISTS (SELECT 1 FROM app.song_member sm JOIN app.member m ON m.id = sm.member_id
        WHERE sm.song_id = s.id AND sm.confirmed AND (m.search_name LIKE :pattern ESCAPE '!'
          OR EXISTS (SELECT 1 FROM app.member_alias a WHERE a.member_id = m.id AND a.search_alias LIKE :pattern ESCAPE '!')))
      OR EXISTS (SELECT 1 FROM app.song_external_artist ea JOIN app.artist a ON a.id = ea.artist_id
        WHERE ea.song_id = s.id AND ea.confirmed AND (a.search_name LIKE :pattern ESCAPE '!'
          OR EXISTS (SELECT 1 FROM app.artist_alias aa WHERE aa.artist_id = a.id AND aa.search_alias LIKE :pattern ESCAPE '!')))
      OR EXISTS (SELECT 1 FROM app.work_artist wa JOIN app.artist a ON a.id = wa.artist_id
        WHERE wa.work_id = s.work_id AND (a.search_name LIKE :pattern ESCAPE '!'
          OR EXISTS (SELECT 1 FROM app.artist_alias aa WHERE aa.artist_id = a.id AND aa.search_alias LIKE :pattern ESCAPE '!')))
      """;

  private String rankedSql() {
    return PublicCatalogSql.SONGS
        + ", ranked AS (SELECT s.*, pv.view_count, pv.observed_at, "
        + RANK
        + " FROM public_songs s LEFT JOIN app.published_video_view pv ON pv.video_id = s.representative_video_id"
        + " AND pv.publication_id = :publication) SELECT * FROM ranked s WHERE true ";
  }

  private Map<String, Object> baseParameters(String text, UUID publication) {
    var params = new HashMap<String, Object>();
    params.put("q", text);
    params.put("pattern", SearchText.likePattern(text));
    params.put("publication", publication);
    return params;
  }

  public List<Row> list(SongQuery query, UUID publication, Position cursor) {
    var sql = new StringBuilder(rankedSql());
    var params = baseParameters(query.text(), publication);
    if (!query.text().isEmpty())
      sql.append(" AND (s.search_rank < 3 OR " + PARTICIPANT_MATCH + ")");
    if (!query.type().equals("ALL")) {
      sql.append(" AND s.song_type = :type");
      params.put("type", query.type());
    }
    if (!query.memberIds().isEmpty()) {
      sql.append(
          " AND EXISTS (SELECT 1 FROM app.song_member sm WHERE sm.song_id = s.id AND sm.confirmed AND sm.member_id IN (:members))");
      params.put("members", query.memberIds());
    }
    if (query.requiredMember() != null) {
      sql.append(
          " AND EXISTS (SELECT 1 FROM app.song_member sm WHERE sm.song_id = s.id AND sm.confirmed AND sm.member_id = :member)");
      params.put("member", query.requiredMember());
    }
    if (query.year() != null) {
      sql.append(" AND s.published_at >= :start AND s.published_at < :end");
      params.put("start", java.sql.Timestamp.from(query.yearStart()));
      params.put("end", java.sql.Timestamp.from(query.yearEnd()));
    }
    if (query.collaboration()) sql.append(" AND s.participant_count > 1");
    if (cursor != null) {
      String dateBoundary = "(s.published_at < :date OR (s.published_at = :date AND s.id < :id))";
      String boundary =
          switch (query.sort()) {
            case "RELEVANCE" ->
                "(s.search_rank > :rank OR (s.search_rank = :rank AND " + dateBoundary + "))";
            case "VIEWS" ->
                cursor.views() == null
                    ? "(s.view_count IS NULL AND " + dateBoundary + ")"
                    : "(s.view_count IS NULL OR s.view_count < :views OR (s.view_count = :views AND "
                        + dateBoundary
                        + "))";
            default -> dateBoundary;
          };
      sql.append(" AND " + boundary);
      params.put("date", java.sql.Timestamp.from(cursor.publishedAt()));
      params.put("id", cursor.id());
      if (query.sort().equals("RELEVANCE")) params.put("rank", cursor.rank());
      if (query.sort().equals("VIEWS") && cursor.views() != null)
        params.put("views", cursor.views());
    }
    sql.append(
        " ORDER BY "
            + switch (query.sort()) {
              case "RELEVANCE" -> "s.search_rank ASC, ";
              case "VIEWS" -> "s.view_count DESC NULLS LAST, ";
              default -> "";
            }
            + "s.published_at DESC, s.id DESC LIMIT :limit");
    params.put("limit", query.size() + 1);
    return jdbc.sql(sql.toString()).params(params).query(this::map).list();
  }

  public Optional<Row> find(UUID id, UUID publication) {
    var params = baseParameters("", publication);
    params.put("id", id);
    return jdbc.sql(rankedSql() + " AND s.id = :id").params(params).query(this::map).optional();
  }

  public List<Row> related(Row song, UUID publication) {
    var params = baseParameters("", publication);
    params.put("id", song.id());
    params.put("work", song.workId());
    return jdbc.sql(
            rankedSql()
                + """
        AND s.id <> :id AND (
          (s.song_type = 'COVER' AND s.work_id = :work) OR
          EXISTS (SELECT 1 FROM app.song_member sm JOIN app.song_member own ON own.member_id = sm.member_id
            WHERE sm.song_id = s.id AND own.song_id = :id AND sm.confirmed AND own.confirmed))
        ORDER BY s.published_at DESC, s.id DESC LIMIT 6
        """)
        .params(params)
        .query(this::map)
        .list();
  }

  // Batch the relationships once per page, rather than one query per card.
  public List<SongDtos.Card> cards(List<Row> rows) {
    if (rows.isEmpty()) return List.of();
    var ids = rows.stream().map(Row::id).toList();
    var participants = new HashMap<UUID, List<SongDtos.Participant>>();
    jdbc.sql(
            """
        SELECT sm.song_id, m.id, m.name, 'MEMBER' AS kind, m.activity_status, sm.position
          FROM app.song_member sm JOIN app.member m ON m.id = sm.member_id WHERE sm.song_id IN (:ids) AND sm.confirmed
        UNION ALL
        SELECT ea.song_id, a.id, a.name, 'EXTERNAL_ARTIST', NULL, ea.position
          FROM app.song_external_artist ea JOIN app.artist a ON a.id = ea.artist_id WHERE ea.song_id IN (:ids) AND ea.confirmed
        ORDER BY song_id, kind DESC, position, id
        """)
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
    var works = new HashMap<UUID, SongDtos.Work>();
    var workIds =
        rows.stream().map(Row::workId).filter(java.util.Objects::nonNull).distinct().toList();
    if (!workIds.isEmpty())
      jdbc.sql(
              """
        SELECT w.id, w.title, a.id AS artist_id, a.name AS artist_name FROM app.musical_work w
          LEFT JOIN app.work_artist wa ON wa.work_id = w.id LEFT JOIN app.artist a ON a.id = wa.artist_id
        WHERE w.id IN (:ids) ORDER BY w.id, wa.position, a.id
        """)
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
