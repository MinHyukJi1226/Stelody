package com.stelody.collection.repository;

import com.stelody.collection.dto.CollectionDtos.*;
import com.stelody.collection.web.CollectionException;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CollectionQueries {
  private static final String VIDEO =
      """
      SELECT r.id,'VIDEO' AS kind,NULL::text AS mode,NULL::uuid AS channel_id,r.logical_slot,
        r.status,r.attempt,r.started_at,r.finished_at,r.error_code,
        t.total,t.observed,t.skipped,t.pending,0 AS pages,0 AS candidates,NULL::text AS rule_version
      FROM app.collection_run r LEFT JOIN LATERAL (
        SELECT count(*) AS total,count(*) FILTER(WHERE outcome='OBSERVED') AS observed,
          count(*) FILTER(WHERE outcome='SKIPPED') AS skipped,
          count(*) FILTER(WHERE outcome='PENDING') AS pending
        FROM app.collection_target WHERE run_id=r.id
      ) t ON true
      """;
  private static final String DISCOVERY =
      """
      SELECT r.id,'DISCOVERY' AS kind,r.mode,r.channel_id,NULL::timestamptz AS logical_slot,
        r.status,1 AS attempt,r.started_at,r.finished_at,r.error_code,
        0 AS total,0 AS observed,0 AS skipped,0 AS pending,r.pages,r.candidates,r.rule_version
      FROM app.discovery_run r
      """;
  private final JdbcClient jdbc;

  public CollectionQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public static String table(String kind) {
    return switch (kind) {
      case "VIDEO" -> "app.collection_run";
      case "DISCOVERY" -> "app.discovery_run";
      default -> throw CollectionException.invalid();
    };
  }

  private String select(String kind) {
    table(kind);
    return kind.equals("VIDEO") ? VIDEO : DISCOVERY;
  }

  public List<Run> list(String kind, int page, int size, String status) {
    return jdbc.sql(
            select(kind)
                + (status == null ? "" : " WHERE r.status=:status")
                + " ORDER BY r.started_at DESC,r.id DESC LIMIT :limit OFFSET :offset")
        .params(status == null ? Map.of() : Map.of("status", status))
        .param("limit", size + 1)
        .param("offset", page * size)
        .query(this::run)
        .list();
  }

  public static String unresolvedFailureWhere(String kind) {
    table(kind);
    return "r.status IN ('FAILED','QUOTA_EXHAUSTED','TIMED_OUT') "
        + "AND coalesce(r.finished_at,r.started_at)>=:from AND coalesce(r.finished_at,r.started_at)<=:to "
        + "AND NOT EXISTS(SELECT 1 FROM app.collection_retry_request retry WHERE retry.kind='"
        + kind
        + "' AND retry.run_id=r.id AND retry.status='SUCCEEDED')";
  }

  public List<Run> failures(Instant from, Instant to, int page, int size) {
    return jdbc.sql(
            "SELECT * FROM ("
                + VIDEO
                + " WHERE "
                + unresolvedFailureWhere("VIDEO")
                + " UNION ALL "
                + DISCOVERY
                + " WHERE "
                + unresolvedFailureWhere("DISCOVERY")
                + ") failures ORDER BY coalesce(finished_at,started_at) DESC,kind,id DESC LIMIT :limit OFFSET :offset")
        .param("from", Timestamp.from(from))
        .param("to", Timestamp.from(to))
        .param("limit", size + 1)
        .param("offset", page * size)
        .query(this::run)
        .list();
  }

  public Run detail(String kind, UUID id) {
    return jdbc.sql(select(kind) + " WHERE r.id=:id")
        .param("id", id)
        .query(this::run)
        .optional()
        .orElseThrow(() -> new CollectionException(404, "COLLECTION_RUN_NOT_FOUND"));
  }

  public Health health(String kind, Instant now) {
    return health(kind, now, false);
  }

  public Health health(String kind, Instant now, boolean springSchedule) {
    var latest = list(kind, 0, 1, null).stream().findFirst().orElse(null);
    String scheduled = kind.equals("VIDEO") ? "" : " AND mode='NEW' AND channel_id IS NULL";
    var success =
        jdbc.sql(
                "SELECT max(finished_at) FROM "
                    + table(kind)
                    + " WHERE status='SUCCEEDED'"
                    + scheduled)
            .query((r, n) -> Optional.ofNullable(instant(r, "max")))
            .single()
            .orElse(null);
    var failure =
        jdbc.sql(
                "SELECT max(finished_at) FROM "
                    + table(kind)
                    + " WHERE status IN ('FAILED','QUOTA_EXHAUSTED','TIMED_OUT')")
            .query((r, n) -> Optional.ofNullable(instant(r, "max")))
            .single()
            .orElse(null);
    var anchor =
        jdbc.sql(
                "SELECT "
                    + (kind.equals("VIDEO") ? "max(logical_slot)" : "max(started_at)")
                    + " FROM "
                    + table(kind)
                    + " WHERE status='SUCCEEDED'"
                    + scheduled)
            .query((r, n) -> Optional.ofNullable(instant(r, "max")))
            .single()
            .orElse(null);
    long period = kind.equals("VIDEO") ? 3600 : springSchedule ? 86400 : 7200;
    long offset = springSchedule && !kind.equals("VIDEO") ? 32400 : 0;
    long due =
        Math.floorDiv(now.getEpochSecond() + offset - (springSchedule ? 0 : 17 * 60), period)
            * period;
    var first =
        anchor == null
            ? jdbc.sql("SELECT min(started_at) FROM " + table(kind) + " WHERE true" + scheduled)
                .query((r, n) -> Optional.ofNullable(instant(r, "min")))
                .single()
                .orElse(null)
            : null;
    var baseline = anchor == null ? first : anchor;
    long missed =
        baseline == null
            ? 0
            : Math.max(
                0,
                (due - Math.floorDiv(baseline.getEpochSecond() + offset, period) * period) / period
                    + (anchor == null ? 1 : 0));
    boolean overdue =
        jdbc.sql(
                "SELECT EXISTS(SELECT 1 FROM "
                    + table(kind)
                    + " WHERE status='RUNNING' AND started_at<:cutoff)")
            .param("cutoff", Timestamp.from(now.minusSeconds(660)))
            .query(Boolean.class)
            .single();
    return new Health(latest, success, failure, missed, missed >= 3, overdue);
  }

  public long pending(boolean deferred) {
    return jdbc.sql(
            "SELECT count(*) FROM app.review_item WHERE review_status='PENDING'"
                + (deferred
                    ? " AND (disposition='DEFERRED' OR source_observed_at<CURRENT_TIMESTAMP-interval '30 days')"
                    : ""))
        .query(Long.class)
        .single();
  }

  public Retry active() {
    return jdbc.sql(
            "SELECT * FROM app.collection_retry_request WHERE status IN ('QUEUED','RUNNING')")
        .query(this::retry)
        .optional()
        .orElse(null);
  }

  public Optional<Retry> findRetry(UUID id) {
    return jdbc.sql("SELECT * FROM app.collection_retry_request WHERE id=:id")
        .param("id", id)
        .query(this::retry)
        .optional();
  }

  public void lockRequests() {
    jdbc.sql("SELECT pg_advisory_xact_lock(2026100401)").query((r, n) -> true).single();
  }

  public void insert(UUID id, String kind, UUID run, int attempt, UUID actor, String reason) {
    jdbc.sql(
            "INSERT INTO app.collection_retry_request(id,kind,run_id,expected_attempt) VALUES(:id,:kind,:run,:attempt)")
        .param("id", id)
        .param("kind", kind)
        .param("run", run)
        .param("attempt", attempt)
        .update();
    jdbc.sql(
            """
        INSERT INTO app.catalog_audit(id,target_type,target_id,actor_id,action,after_value,reason)
        VALUES(:audit,'COLLECTION_RETRY',:id,:actor,'REQUEST',
          jsonb_build_object('kind',CAST(:kind AS text),'runId',CAST(:run AS text),'attempt',CAST(:attempt AS integer)),:reason)
        """)
        .param("audit", UUID.randomUUID())
        .param("id", id)
        .param("actor", actor)
        .param("kind", kind)
        .param("run", run)
        .param("attempt", attempt)
        .param("reason", reason)
        .update();
  }

  private Run run(ResultSet r, int n) throws SQLException {
    return new Run(
        r.getObject("id", UUID.class),
        r.getString("kind"),
        r.getString("mode"),
        r.getObject("channel_id", UUID.class),
        instant(r, "logical_slot"),
        r.getString("status"),
        r.getInt("attempt"),
        instant(r, "started_at"),
        instant(r, "finished_at"),
        r.getString("error_code"),
        r.getLong("total"),
        r.getLong("observed"),
        r.getLong("skipped"),
        r.getLong("pending"),
        r.getInt("pages"),
        r.getInt("candidates"),
        r.getString("rule_version"));
  }

  private Retry retry(ResultSet r, int n) throws SQLException {
    return new Retry(
        r.getObject("id", UUID.class),
        r.getString("kind"),
        r.getObject("run_id", UUID.class),
        r.getInt("expected_attempt"),
        r.getString("status"),
        instant(r, "created_at"),
        instant(r, "started_at"),
        instant(r, "finished_at"),
        r.getObject("execution_run_id", UUID.class),
        r.getString("error_code"));
  }

  private static Instant instant(ResultSet r, String column) throws SQLException {
    var value = r.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
