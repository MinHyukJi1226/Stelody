package com.stelody.review.repository;

import com.stelody.collector.domain.CoverPublicationRules;
import com.stelody.review.dto.ReviewDtos;
import com.stelody.review.web.ReviewException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ReviewQueries {
  public static final String EFFECTIVE_DISPOSITION =
      "CASE WHEN r.source_observed_at <= CURRENT_TIMESTAMP - INTERVAL '30 days' THEN 'DEFERRED' ELSE r.disposition END";
  private static final String SELECT =
      """
      SELECT r.*,c.name AS channel_name,v.song_id AS registered_song_id
      FROM app.review_item r JOIN app.channel c ON c.id=r.channel_id
      LEFT JOIN app.video v ON v.id=r.registered_video_id
      """;
  private final JdbcClient jdbc;
  private final ObjectMapper mapper;

  public ReviewQueries(JdbcClient jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public ReviewDtos.CompletionPreview completionPreview() {
    var rows =
        jdbc.sql(
                SELECT
                    + " WHERE r.review_status='PENDING' AND ("
                    + EFFECTIVE_DISPOSITION
                    + ")='REVIEW' ORDER BY r.first_seen_at DESC,r.id DESC LIMIT 10001")
            .query(this::item)
            .list();
    var members = new java.util.LinkedHashMap<UUID, UUID>();
    jdbc.sql(
            "SELECT id,member_id FROM app.channel WHERE channel_type='MEMBER' AND member_id IS NOT NULL")
        .query(
            (r, n) -> {
              members.put(r.getObject(1, UUID.class), r.getObject(2, UUID.class));
              return true;
            })
        .list();
    return new ReviewDtos.CompletionPreview(
        rows.subList(0, Math.min(10000, rows.size())), rows.size() > 10000, members);
  }

  public record BatchRow(
      UUID id,
      long version,
      String status,
      String disposition,
      UUID batch,
      String hash,
      UUID songId) {}

  public boolean requestChanged(UUID requestId, String hash) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(CAST(:id AS text),20261027))")
        .param("id", requestId)
        .query((r, n) -> true)
        .single();
    return jdbc.sql(
            "SELECT EXISTS(SELECT 1 FROM app.review_item WHERE registered_batch_id=:batch AND registered_batch_hash<>:hash)")
        .param("batch", requestId)
        .param("hash", hash)
        .query(Boolean.class)
        .single();
  }

  public List<BatchRow> lockBatch(List<ReviewDtos.Candidate> candidates) {
    return jdbc.sql(
            "SELECT r.id,r.version,r.review_status,("
                + EFFECTIVE_DISPOSITION
                + ") AS effective_disposition,r.registered_batch_id,r.registered_batch_hash,v.song_id FROM app.review_item r LEFT JOIN app.video v ON v.id=r.registered_video_id WHERE r.id IN (:ids) ORDER BY r.id FOR UPDATE OF r")
        .param("ids", candidates.stream().map(ReviewDtos.Candidate::id).toList())
        .query(
            (r, n) ->
                new BatchRow(
                    r.getObject(1, UUID.class),
                    r.getLong(2),
                    r.getString(3),
                    r.getString(4),
                    r.getObject(5, UUID.class),
                    r.getString(6),
                    r.getObject(7, UUID.class)))
        .list();
  }

  public List<CoverPublicationRules.Member> registrationMembers() {
    return jdbc.sql("SELECT id,name,version FROM app.member ORDER BY id FOR SHARE")
        .query(
            (r, n) -> {
              UUID id = r.getObject("id", UUID.class);
              var names = new java.util.ArrayList<String>();
              names.add(r.getString("name"));
              names.addAll(
                  jdbc.sql("SELECT alias FROM app.member_alias WHERE member_id=:id ORDER BY alias")
                      .param("id", id)
                      .query(String.class)
                      .list());
              return new CoverPublicationRules.Member(id, r.getLong("version"), List.copyOf(names));
            })
        .list();
  }

  public void rememberBatch(UUID review, UUID requestId, String hash) {
    jdbc.sql(
            "UPDATE app.review_item SET registered_batch_id=:batch,registered_batch_hash=:hash WHERE id=:id AND review_status='REGISTERED'")
        .param("batch", requestId)
        .param("hash", hash)
        .param("id", review)
        .update();
  }

  public int complete(ReviewDtos.Complete body, String hash, UUID actor) {
    return jdbc.sql(
            "SELECT app.complete_review_candidates(CAST(:items AS jsonb),CAST(:selected AS jsonb),:batch,:hash,:actor)")
        .param("items", mapper.writeValueAsString(body.items()))
        .param(
            "selected",
            mapper.writeValueAsString(
                body.selected().stream().map(ReviewDtos.Selection::id).toList()))
        .param("batch", body.requestId())
        .param("hash", hash)
        .param("actor", actor)
        .query(Integer.class)
        .single();
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
                + " WHERE (:status='ALL' OR r.review_status=:status) "
                + (disposition == null ? "" : "AND (" + EFFECTIVE_DISPOSITION + ")=:disposition ")
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
        expired,
        rs.getObject("registered_video_id", UUID.class),
        rs.getObject("registered_song_id", UUID.class));
  }

  private Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }
}
