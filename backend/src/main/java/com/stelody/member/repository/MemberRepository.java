package com.stelody.member.repository;

import com.stelody.catalog.repository.PublicCatalogSql;
import com.stelody.member.dto.MemberDtos;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MemberRepository {
  public record Row(MemberDtos.Card card, String searchName, int activityOrder) {}

  public record Position(
      int version, String filter, int activityOrder, String searchName, UUID id) {}

  private final JdbcClient jdbc;

  public MemberRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static final String CARDS =
      PublicCatalogSql.SONGS
          + """
      , counts AS (
        SELECT sm.member_id, count(*) AS total,
          count(*) FILTER (WHERE s.song_type = 'ORIGINAL') AS originals,
          count(*) FILTER (WHERE s.song_type = 'COVER') AS covers,
          count(*) FILTER (WHERE s.participant_count > 1) AS collaborations
        FROM public_songs s JOIN app.song_member sm ON sm.song_id = s.id AND sm.confirmed
        GROUP BY sm.member_id
      ), members AS (
        SELECT m.*, coalesce(c.total, 0) AS total, coalesce(c.originals, 0) AS originals,
          coalesce(c.covers, 0) AS covers, coalesce(c.collaborations, 0) AS collaborations,
          CASE WHEN m.activity_status = 'ACTIVE' THEN 1 ELSE 2 END AS activity_order
        FROM app.member m LEFT JOIN counts c ON c.member_id = m.id
      ) SELECT * FROM members m WHERE true
      """;

  public List<Row> list(String status, int size, Position cursor) {
    var sql = new StringBuilder(CARDS);
    var params = new HashMap<String, Object>();
    if (!status.equals("ALL")) {
      sql.append(" AND m.activity_status = :status");
      params.put("status", status);
    }
    if (cursor != null) {
      sql.append(
          """
          AND (m.activity_order > :activity OR (m.activity_order = :activity AND
            (m.search_name COLLATE "C" > :name COLLATE "C" OR (m.search_name = :name AND m.id > :id))))
          """);
      params.put("activity", cursor.activityOrder());
      params.put("name", cursor.searchName());
      params.put("id", cursor.id());
    }
    sql.append(" ORDER BY m.activity_order, m.search_name COLLATE \"C\", m.id LIMIT :limit");
    params.put("limit", size + 1);
    return jdbc.sql(sql.toString()).params(params).query(this::map).list();
  }

  public Optional<MemberDtos.Card> find(UUID id) {
    return jdbc.sql(CARDS + " AND m.id = :id")
        .param("id", id)
        .query(this::map)
        .optional()
        .map(Row::card);
  }

  public List<MemberDtos.Channel> channels(UUID member) {
    return jdbc.sql(
            "SELECT name, youtube_id FROM app.channel WHERE member_id = :id AND channel_type = 'MEMBER' ORDER BY name, id")
        .param("id", member)
        .query(
            (rs, n) ->
                new MemberDtos.Channel(
                    rs.getString("name"),
                    rs.getString("youtube_id"),
                    "https://www.youtube.com/channel/" + rs.getString("youtube_id")))
        .list();
  }

  private Row map(ResultSet rs, int n) throws SQLException {
    var generation = rs.getObject("generation");
    return new Row(
        new MemberDtos.Card(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            generation == null ? null : ((Number) generation).intValue(),
            rs.getString("activity_status"),
            rs.getString("profile_image_url"),
            new MemberDtos.Counts(
                rs.getLong("total"),
                rs.getLong("originals"),
                rs.getLong("covers"),
                rs.getLong("collaborations"))),
        rs.getString("search_name"),
        rs.getInt("activity_order"));
  }
}
