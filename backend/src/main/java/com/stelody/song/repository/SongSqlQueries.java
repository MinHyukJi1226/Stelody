package com.stelody.song.repository;

import com.stelody.catalog.domain.SearchText;
import com.stelody.catalog.repository.PublicCatalogSql;
import com.stelody.song.domain.SongCursor.Position;
import com.stelody.song.domain.SongQuery;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** SQL construction only; execution and result mapping belong to SongRepository. */
final class SongSqlQueries {
  record Statement(String sql, Map<String, Object> parameters) {}

  private SongSqlQueries() {}

  static final String PUBLICATION =
      "SELECT view_publication_id FROM app.catalog_state WHERE singleton";

  static final String RECOMMENDATION_CANDIDATES =
      PublicCatalogSql.SONGS
          + """
          SELECT s.id, sm.member_id FROM public_songs s
          JOIN app.song_member sm ON sm.song_id = s.id AND sm.confirmed
          ORDER BY s.id, sm.member_id
          """;

  static final String PARTICIPANTS =
      """
      SELECT sm.song_id, m.id, m.name, 'MEMBER' AS kind, m.activity_status, sm.position
        FROM app.song_member sm JOIN app.member m ON m.id = sm.member_id
        WHERE sm.song_id IN (:ids) AND sm.confirmed
      UNION ALL
      SELECT ea.song_id, a.id, a.name, 'EXTERNAL_ARTIST', NULL, ea.position
        FROM app.song_external_artist ea JOIN app.artist a ON a.id = ea.artist_id
        WHERE ea.song_id IN (:ids) AND ea.confirmed
      ORDER BY song_id, kind DESC, position, id
      """;

  static final String WORKS =
      """
      SELECT w.id, w.title, a.id AS artist_id, a.name AS artist_name FROM app.musical_work w
        LEFT JOIN app.work_artist wa ON wa.work_id = w.id
        LEFT JOIN app.artist a ON a.id = wa.artist_id
      WHERE w.id IN (:ids) ORDER BY w.id, wa.position, a.id
      """;

  private static final String SEARCH_RANK =
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

  private static final String RANKED_SONGS =
      PublicCatalogSql.SONGS
          + ", ranked AS (SELECT s.*, pv.view_count, pv.observed_at, "
          + SEARCH_RANK
          + """
           FROM public_songs s LEFT JOIN app.published_video_view pv
             ON pv.video_id = s.representative_video_id AND pv.publication_id = :publication)
          SELECT * FROM ranked s WHERE true
          """;

  private static final String DATE_BOUNDARY =
      "(s.published_at < :date OR (s.published_at = :date AND s.id < :id))";

  static Statement list(SongQuery query, UUID publication, Position cursor) {
    var sql = new StringBuilder(RANKED_SONGS);
    var params = baseParameters(query.text(), publication);
    appendFilters(sql, params, query);
    appendCursor(sql, params, query.sort(), cursor);
    sql.append(" ORDER BY ").append(orderBy(query.sort())).append(" LIMIT :limit");
    params.put("limit", query.size() + 1);
    return new Statement(sql.toString(), params);
  }

  static Statement find(UUID id, UUID publication) {
    var params = baseParameters("", publication);
    params.put("id", id);
    return new Statement(RANKED_SONGS + " AND s.id = :id", params);
  }

  static Statement findAll(List<UUID> ids, UUID publication) {
    var params = baseParameters("", publication);
    params.put("ids", ids);
    return new Statement(RANKED_SONGS + " AND s.id IN (:ids)", params);
  }

  static Statement related(UUID id, UUID work, UUID publication) {
    var params = baseParameters("", publication);
    params.put("id", id);
    params.put("work", work);
    return new Statement(
        RANKED_SONGS
            + """
        AND s.id <> :id AND (
          (s.song_type = 'COVER' AND s.work_id = :work) OR
          EXISTS (SELECT 1 FROM app.song_member sm JOIN app.song_member own ON own.member_id = sm.member_id
            WHERE sm.song_id = s.id AND own.song_id = :id AND sm.confirmed AND own.confirmed))
        ORDER BY s.published_at DESC, s.id DESC LIMIT 6
        """,
        params);
  }

  private static Map<String, Object> baseParameters(String text, UUID publication) {
    var params = new HashMap<String, Object>();
    params.put("q", text);
    params.put("pattern", SearchText.likePattern(text));
    params.put("publication", publication);
    return params;
  }

  private static void appendFilters(
      StringBuilder sql, Map<String, Object> params, SongQuery query) {
    if (!query.text().isEmpty())
      sql.append(" AND (s.search_rank < 3 OR ").append(PARTICIPANT_MATCH).append(")");
    if (!query.type().equals("ALL")) {
      sql.append(" AND s.song_type = :type");
      params.put("type", query.type());
    }
    if (!query.memberIds().isEmpty()) {
      sql.append(
          """
           AND EXISTS (SELECT 1 FROM app.song_member sm WHERE sm.song_id = s.id
             AND sm.confirmed AND sm.member_id IN (:members))
          """);
      params.put("members", query.memberIds());
    }
    if (query.requiredMember() != null) {
      sql.append(
          """
           AND EXISTS (SELECT 1 FROM app.song_member sm WHERE sm.song_id = s.id
             AND sm.confirmed AND sm.member_id = :member)
          """);
      params.put("member", query.requiredMember());
    }
    if (query.year() != null) {
      sql.append(" AND s.published_at >= :start AND s.published_at < :end");
      params.put("start", Timestamp.from(query.yearStart()));
      params.put("end", Timestamp.from(query.yearEnd()));
    }
    if (query.collaboration()) sql.append(" AND s.participant_count > 1");
  }

  private static void appendCursor(
      StringBuilder sql, Map<String, Object> params, String sort, Position cursor) {
    if (cursor == null) return;
    sql.append(" AND ").append(cursorBoundary(sort, cursor));
    params.put("date", Timestamp.from(cursor.publishedAt()));
    params.put("id", cursor.id());
    if (sort.equals("RELEVANCE")) params.put("rank", cursor.rank());
    if (sort.equals("VIEWS") && cursor.views() != null) params.put("views", cursor.views());
  }

  private static String cursorBoundary(String sort, Position cursor) {
    return switch (sort) {
      case "RELEVANCE" ->
          "(s.search_rank > :rank OR (s.search_rank = :rank AND " + DATE_BOUNDARY + "))";
      case "VIEWS" ->
          cursor.views() == null
              ? "(s.view_count IS NULL AND " + DATE_BOUNDARY + ")"
              : "(s.view_count IS NULL OR s.view_count < :views OR (s.view_count = :views AND "
                  + DATE_BOUNDARY
                  + "))";
      default -> DATE_BOUNDARY;
    };
  }

  private static String orderBy(String sort) {
    String primary =
        switch (sort) {
          case "RELEVANCE" -> "s.search_rank ASC, ";
          case "VIEWS" -> "s.view_count DESC NULLS LAST, ";
          default -> "";
        };
    return primary + "s.published_at DESC, s.id DESC";
  }
}
