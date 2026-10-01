package com.stelody.review.repository;

import com.stelody.review.dto.ReviewDtos;
import com.stelody.review.web.ReviewException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewQueries {
  private static final String SELECT =
      """
      SELECT r.*,c.name AS channel_name FROM app.review_item r JOIN app.channel c ON c.id=r.channel_id
      """;
  private final JdbcClient jdbc;

  public ReviewQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public ReviewDtos.Item detail(UUID id) {
    return jdbc.sql(SELECT + " WHERE r.id=:id")
        .param("id", id)
        .query(this::item)
        .optional()
        .orElseThrow(() -> new ReviewException(404, "REVIEW_NOT_FOUND", "검토 후보를 찾을 수 없습니다"));
  }

  public List<ReviewDtos.Item> list(int page, int size, String status, String disposition) {
    return jdbc.sql(
            SELECT
                + " WHERE r.review_status=:status "
                + (disposition == null
                    ? ""
                    : "AND (CASE WHEN r.source_observed_at <= CURRENT_TIMESTAMP - INTERVAL '30 days' "
                        + "THEN 'DEFERRED' ELSE r.disposition END)=:disposition ")
                + "ORDER BY r.first_seen_at DESC,r.id DESC LIMIT :limit OFFSET :offset")
        .param("status", status)
        .params(
            disposition == null ? java.util.Map.of() : java.util.Map.of("disposition", disposition))
        .param("limit", size + 1)
        .param("offset", page * size)
        .query(this::item)
        .list();
  }

  public void audit(
      UUID review,
      UUID actor,
      String before,
      String after,
      String oldNote,
      String note,
      Instant now) {
    jdbc.sql(
            """
        INSERT INTO app.admin_audit(id,review_id,actor_id,before_status,after_status,before_note,after_note,changed_at)
        VALUES(:id,:review,:actor,:before,:after,:oldNote,:note,:now)
        """)
        .param("id", UUID.randomUUID())
        .param("review", review)
        .param("actor", actor)
        .param("before", before)
        .param("after", after)
        .param("oldNote", oldNote)
        .param("note", note)
        .param("now", Timestamp.from(now))
        .update();
  }

  private ReviewDtos.Item item(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    Instant observed = rs.getTimestamp("source_observed_at").toInstant();
    boolean expired = !observed.isAfter(Instant.now().minusSeconds(2592000));
    return new ReviewDtos.Item(
        rs.getObject("id", UUID.class),
        rs.getString("youtube_id"),
        rs.getObject("channel_id", UUID.class),
        rs.getString("channel_name"),
        expired ? null : rs.getString("source_title"),
        expired ? null : instant(rs.getTimestamp("source_published_at")),
        expired ? null : rs.getString("source_thumbnail_url"),
        expired ? null : rs.getObject("source_duration_seconds", Long.class),
        observed,
        expired ? "UNAVAILABLE" : rs.getString("availability"),
        expired ? "DEFERRED" : rs.getString("disposition"),
        expired ? "UNKNOWN" : rs.getString("suggested_type"),
        rs.getString("rule_version"),
        expired ? "SOURCE_EXPIRED" : rs.getString("decision_reason"),
        rs.getString("review_status"),
        rs.getString("review_note"),
        rs.getTimestamp("first_seen_at").toInstant(),
        rs.getLong("version"),
        expired);
  }

  private Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }
}
