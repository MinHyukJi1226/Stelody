package com.stelody.collector.service;

import com.stelody.collector.config.*;
import com.stelody.collector.domain.*;
import com.stelody.collector.repository.CollectionRepository;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses the dedicated collector role, global collection lock and shared time budget. */
public final class CollectionRetryWorker {
  private record Request(
      UUID id, String kind, UUID run, int attempt, String status, UUID execution, UUID lease) {}

  private record Target(String status, int attempt, Instant since, String mode, UUID channel) {}

  private final DataSource source;
  private final CollectorSettings settings;
  private final Clock clock;
  private final CollectionBudget.Sleeper sleeper;
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;

  public CollectionRetryWorker(
      DataSource source,
      CollectorSettings settings,
      Clock clock,
      CollectionBudget.Sleeper sleeper) {
    this.source = source;
    this.settings = settings;
    this.clock = clock;
    this.sleeper = sleeper;
    jdbc = JdbcClient.create(source);
    tx = new TransactionTemplate(new JdbcTransactionManager(source));
    tx.setTimeout(10);
  }

  public Optional<VideoCollector.Result> collect(
      CollectionBudget budget, boolean discoveryEnabled, boolean classificationAllowed) {
    return collect(budget, discoveryEnabled, classificationAllowed, false);
  }

  public Optional<VideoCollector.Result> collect(
      CollectionBudget budget,
      boolean discoveryEnabled,
      boolean classificationAllowed,
      boolean autoPublicationPolicyAllowed) {
    if (!settings.enabled()) return Optional.empty();
    try {
      settings.validate();
      new CollectionRepository(source).checkPrivileges();
      try (var lock = CollectionLock.acquire(source)) {
        if (lock == null)
          return Optional.of(new VideoCollector.Result(null, "SKIPPED_LOCKED", null));
        lock.check();
        var request =
            tx.execute(
                s -> {
                  jdbc.sql("DELETE FROM app.collection_retry_request WHERE finished_at<:cutoff")
                      .param("cutoff", Timestamp.from(clock.instant().minusSeconds(2592000)))
                      .update();
                  return jdbc.sql(
                          "SELECT * FROM app.collection_retry_request WHERE status IN ('QUEUED','RUNNING') ORDER BY created_at,id LIMIT 1 FOR UPDATE")
                      .query(
                          (r, n) ->
                              new Request(
                                  r.getObject("id", UUID.class),
                                  r.getString("kind"),
                                  r.getObject("run_id", UUID.class),
                                  r.getInt("expected_attempt"),
                                  r.getString("status"),
                                  r.getObject("execution_run_id", UUID.class),
                                  r.getObject("lease_token", UUID.class)))
                      .optional();
                });
        if (request.isEmpty()) return Optional.empty();
        var value = request.get();
        var target =
            target(value.kind(), value.execution() == null ? value.run() : value.execution());
        if (target.isEmpty()) return reject(value, "RETRY_RUN_MISSING");
        var current = target.get();
        if (current.status().equals("SUCCEEDED")) {
          lock.check();
          finish(
              value.id(),
              value.lease(),
              "SUCCEEDED",
              value.execution() == null ? value.run() : value.execution(),
              null);
          return Optional.of(new VideoCollector.Result(value.run(), "SKIPPED_COMPLETED", null));
        }
        var original = target(value.kind(), value.run()).orElse(null);
        if (original == null
            || original.since().isBefore(clock.instant().minusSeconds(172800))
            || original.since().isAfter(clock.instant())) return reject(value, "RETRY_EXPIRED");
        if (value.status().equals("QUEUED")
            && !value.kind().equals("DISCOVERY")
            && original.attempt() != value.attempt()) return reject(value, "RETRY_RUN_CHANGED");
        if (value.kind().equals("DISCOVERY") && !discoveryEnabled)
          return reject(value, "DISCOVERY_DISABLED");
        UUID lease = UUID.randomUUID();
        lock.check();
        if (jdbc.sql(
                    "UPDATE app.collection_retry_request SET status='RUNNING',lease_token=:lease,started_at=:now,finished_at=NULL,error_code=NULL WHERE id=:id AND status IN ('QUEUED','RUNNING') AND lease_token IS NOT DISTINCT FROM :oldLease")
                .param("lease", lease)
                .param("oldLease", value.lease())
                .param("now", Timestamp.from(clock.instant()))
                .param("id", value.id())
                .update()
            != 1) throw new CollectionFailure("LOCK_LOST", false);
        var started =
            (java.util.function.Consumer<UUID>)
                id -> {
                  if (jdbc.sql(
                              "UPDATE app.collection_retry_request SET execution_run_id=:run WHERE id=:id AND lease_token=:lease AND status='RUNNING'")
                          .param("run", id)
                          .param("id", value.id())
                          .param("lease", lease)
                          .update()
                      != 1) throw new CollectionFailure("LOCK_LOST", false);
                };
        VideoCollector.Result result;
        if (value.kind().equals("VIDEO")) {
          result =
              new VideoCollector(source, settings, clock, sleeper)
                  .retry(budget, value.run(), started);
        } else {
          var options =
              new DiscoveryOptions(
                  classificationAllowed,
                  original.channel(),
                  original.mode().equals("BACKFILL"),
                  original.mode().equals("BACKFILL") ? 1 : 3,
                  autoPublicationPolicyAllowed);
          result =
              new DiscoveryCollector(source, settings, options, clock, sleeper)
                  .retry(budget, started);
        }
        lock.check();
        finish(
            value.id(),
            lease,
            result.status().equals("SUCCEEDED") ? "SUCCEEDED" : "FAILED",
            result.runId(),
            result.code());
        return Optional.of(result);
      }
    } catch (Exception error) {
      // Keep a claimed request recoverable when a lock/connection dies; never log its raw error.
      return Optional.of(new VideoCollector.Result(null, "FAILED", "RETRY_PROCESSING_FAILED"));
    }
  }

  private Optional<Target> target(String kind, UUID id) {
    return jdbc.sql(
            kind.equals("VIDEO")
                ? "SELECT status,attempt,logical_slot AS since,NULL::text AS mode,NULL::uuid AS channel_id FROM app.collection_run WHERE id=:id"
                : "SELECT status,1 AS attempt,started_at AS since,mode,channel_id FROM app.discovery_run WHERE id=:id")
        .param("id", id)
        .query(
            (r, n) ->
                new Target(
                    r.getString("status"),
                    r.getInt("attempt"),
                    r.getTimestamp("since").toInstant(),
                    r.getString("mode"),
                    r.getObject("channel_id", UUID.class)))
        .optional();
  }

  private Optional<VideoCollector.Result> reject(Request request, String code) {
    finish(request.id(), request.lease(), "FAILED", request.execution(), code);
    return Optional.of(new VideoCollector.Result(request.run(), "FAILED", code));
  }

  private void finish(UUID id, UUID lease, String status, UUID run, String code) {
    int updated =
        jdbc.sql(
                "UPDATE app.collection_retry_request SET status=:status,execution_run_id=:run,error_code=:code,finished_at=:now,lease_token=NULL WHERE id=:id AND status IN ('QUEUED','RUNNING') AND lease_token IS NOT DISTINCT FROM :lease")
            .param("id", id)
            .param("status", status)
            .param("run", run)
            .param("code", code)
            .param("now", Timestamp.from(clock.instant()))
            .param("lease", lease)
            .update();
    if (updated != 1) throw new CollectionFailure("LOCK_LOST", false);
  }
}
