package com.stelody.collector.repository;

import com.stelody.collector.domain.CollectionFailure;
import com.stelody.collector.domain.VideoObservation;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class CollectionRepository {
  public record Run(UUID id, Instant slot, boolean completed) {}

  public record Target(UUID videoId, String youtubeId, String channelId) {}

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

  public CollectionRepository(DataSource source) {
    jdbc = JdbcClient.create(source);
    transactions = new TransactionTemplate(new JdbcTransactionManager(source));
    transactions.setTimeout(10);
  }

  public void checkPrivileges() {
    boolean privateAccess =
        jdbc.sql(
                """
        SELECT rolsuper OR rolcreaterole OR has_schema_privilege(current_user, 'app', 'CREATE') OR
                      EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE ((n.nspname = 'app' AND c.relname IN ('app_user', 'favorite', 'playlist', 'playlist_item'))
                          OR (n.nspname = 'session' AND c.relname IN ('spring_session', 'spring_session_attributes')))
                          AND has_table_privilege(current_user, c.oid, 'SELECT'))
                    FROM pg_roles WHERE rolname = current_user
        """)
            .query(Boolean.class)
            .single();
    if (privateAccess) throw new CollectionFailure("COLLECTOR_ROLE_TOO_BROAD", false);
  }

  public Run begin(UUID token, Instant slot, Instant now) {
    return transactions.execute(
        status -> {
          jdbc.sql("SET LOCAL statement_timeout = '10s'").update();
          jdbc.sql("UPDATE app.collection_control SET owner_token = :token WHERE singleton")
              .param("token", token)
              .update();
          jdbc.sql(
                  """
          UPDATE app.collection_run SET status = 'FAILED', error_code = 'INTERRUPTED', finished_at = :now
          WHERE status = 'RUNNING'
          """)
              .param("now", ts(now))
              .update();
          var previous =
              jdbc.sql(
                      """
          SELECT id, logical_slot FROM app.collection_run
          WHERE status <> 'SUCCEEDED' AND logical_slot >= :oldest AND logical_slot <= :slot
          ORDER BY logical_slot LIMIT 1
          """)
                  .param("oldest", ts(now.minusSeconds(172800)))
                  .param("slot", ts(slot))
                  .query(
                      (rs, n) ->
                          new Run(
                              rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), false))
                  .optional();
          if (previous.isPresent()) {
            Run run = previous.get();
            jdbc.sql(
                    """
            UPDATE app.collection_run SET status = 'RUNNING', attempt = attempt + 1,
              started_at = :now, finished_at = NULL, error_code = NULL WHERE id = :id
            """)
                .param("now", ts(now))
                .param("id", run.id())
                .update();
            return run;
          }
          var done =
              jdbc.sql("SELECT id, logical_slot FROM app.collection_run WHERE logical_slot = :slot")
                  .param("slot", ts(slot))
                  .query(
                      (rs, n) ->
                          new Run(
                              rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), true))
                  .optional();
          if (done.isPresent()) return done.get();
          UUID id = UUID.randomUUID();
          jdbc.sql(
                  """
          INSERT INTO app.collection_run(id, logical_slot, status, attempt, started_at)
          VALUES (:id, :slot, 'RUNNING', 1, :now)
          """)
              .param("id", id)
              .param("slot", ts(slot))
              .param("now", ts(now))
              .update();
          jdbc.sql(
                  """
          INSERT INTO app.collection_target(run_id, video_id, youtube_id, channel_youtube_id)
          SELECT :id, v.id, v.youtube_id, c.youtube_id
          FROM app.video v JOIN app.channel c ON c.id = v.channel_id
          WHERE c.collection_enabled AND c.channel_type IN ('GROUP', 'MEMBER')
          """)
              .param("id", id)
              .update();
          return new Run(id, slot, false);
        });
  }

  public List<Target> pending(UUID runId) {
    return jdbc.sql(
            """
        SELECT video_id, youtube_id, channel_youtube_id FROM app.collection_target
        WHERE run_id = :id AND outcome = 'PENDING' ORDER BY video_id LIMIT 50
        """)
        .param("id", runId)
        .query((rs, n) -> new Target(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
        .list();
  }

  public void save(
      UUID token,
      Run run,
      List<Target> batch,
      Map<String, VideoObservation> observations,
      Instant observedAt) {
    guarded(
        token,
        () -> {
          // Validate every item before any change, including an API channel mismatch.
          for (Target target : batch) {
            var value = observations.get(target.youtubeId());
            if (value == null
                || (value.channelId() != null && !target.channelId().equals(value.channelId())))
              throw new CollectionFailure("INVALID_RESPONSE", false);
          }
          for (Target target : batch) {
            var value = observations.get(target.youtubeId());
            int updated =
                jdbc.sql(CollectionSql.UPDATE_VIDEO)
                    .param("videoId", target.videoId())
                    .param("youtubeId", target.youtubeId())
                    .param("channelId", target.channelId())
                    .param("availability", value.availability())
                    .param("title", value.title())
                    .param("publishedAt", ts(value.publishedAt()))
                    .param("thumbnail", value.thumbnailUrl())
                    .param("duration", value.durationSeconds())
                    .param("embeddable", value.embeddable())
                    .param("observedAt", ts(observedAt))
                    .update();
            jdbc.sql(
                    """
            UPDATE app.collection_target SET outcome = :outcome, observed_at = :observedAt,
              view_count = :views WHERE run_id = :runId AND video_id = :videoId AND outcome = 'PENDING'
            """)
                .param("outcome", updated == 1 ? "OBSERVED" : "SKIPPED")
                .param("observedAt", ts(observedAt))
                .param("views", updated == 1 ? value.viewCount() : null)
                .param("runId", run.id())
                .param("videoId", target.videoId())
                .update();
            if (updated == 1 && value.viewCount() != null) {
              jdbc.sql(
                      """
              INSERT INTO app.view_snapshot(video_id, logical_slot, observed_at, view_count)
              VALUES (:videoId, :slot, :observedAt, :views) ON CONFLICT DO NOTHING
              """)
                  .param("videoId", target.videoId())
                  .param("slot", ts(run.slot()))
                  .param("observedAt", ts(observedAt))
                  .param("views", value.viewCount())
                  .update();
              jdbc.sql(
                      """
              INSERT INTO app.daily_video_view(video_id, day, observed_at, view_count)
              VALUES (:videoId, :day, :observedAt, :views)
              ON CONFLICT (video_id, day) DO UPDATE SET observed_at = EXCLUDED.observed_at,
                view_count = EXCLUDED.view_count
              WHERE app.daily_video_view.observed_at < EXCLUDED.observed_at
              """)
                  .param("videoId", target.videoId())
                  .param("day", LocalDate.ofInstant(observedAt, ZoneId.of("Asia/Seoul")))
                  .param("observedAt", ts(observedAt))
                  .param("views", value.viewCount())
                  .update();
            }
          }
          return null;
        });
  }

  public void publish(UUID token, Run run, Instant now) {
    guarded(
        token,
        () -> {
          if (jdbc.sql(
                      "SELECT count(*) FROM app.collection_target WHERE run_id = :id AND outcome = 'PENDING'")
                  .param("id", run.id())
                  .query(Long.class)
                  .single()
              != 0) throw new CollectionFailure("INCOMPLETE_RUN", false);
          long total =
              jdbc.sql("SELECT count(*) FROM app.collection_target WHERE run_id = :id")
                  .param("id", run.id())
                  .query(Long.class)
                  .single();
          if (total > 0) {
            UUID publication = UUID.randomUUID();
            jdbc.sql("INSERT INTO app.view_publication(id, published_at) VALUES (:id, :now)")
                .param("id", publication)
                .param("now", ts(now))
                .update();
            jdbc.sql(
                    """
            INSERT INTO app.published_video_view(publication_id, video_id, view_count, observed_at)
            SELECT :publication, t.video_id, t.view_count, t.observed_at FROM app.collection_target t
            JOIN app.video v ON v.id = t.video_id
            WHERE t.run_id = :runId AND t.outcome = 'OBSERVED' AND t.view_count IS NOT NULL
              AND t.observed_at >= :oldest AND v.availability = 'PUBLIC'
            """)
                .param("publication", publication)
                .param("runId", run.id())
                .param("oldest", ts(now.minusSeconds(30L * 86400)))
                .update();
            jdbc.sql("UPDATE app.catalog_state SET view_publication_id = :id WHERE singleton")
                .param("id", publication)
                .update();
            jdbc.sql("UPDATE app.collection_run SET publication_id = :publication WHERE id = :id")
                .param("publication", publication)
                .param("id", run.id())
                .update();
            jdbc.sql(
                    """
            DELETE FROM app.published_video_view WHERE publication_id <> :id
            """)
                .param("id", publication)
                .update();
            jdbc.sql("DELETE FROM app.view_publication WHERE id <> :id")
                .param("id", publication)
                .update();
          }
          jdbc.sql(
                  """
          UPDATE app.collection_run SET status = 'SUCCEEDED', finished_at = :now, error_code = NULL WHERE id = :id
          """)
              .param("id", run.id())
              .param("now", ts(now))
              .update();
          return null;
        });
  }

  public void fail(UUID token, UUID runId, String code, Instant now) {
    guarded(
        token,
        () -> {
          String status =
              switch (code) {
                case "QUOTA_EXHAUSTED" -> "QUOTA_EXHAUSTED";
                case "TIME_LIMIT" -> "TIMED_OUT";
                default -> "FAILED";
              };
          jdbc.sql(
                  "UPDATE app.collection_run SET status = :status, error_code = :code, finished_at = :now WHERE id = :id AND status = 'RUNNING'")
              .param("status", status)
              .param("code", code)
              .param("now", ts(now))
              .param("id", runId)
              .update();
          return null;
        });
  }

  public void cleanup(UUID token, Instant now) {
    guarded(
        token,
        () -> {
          jdbc.sql("DELETE FROM app.view_snapshot WHERE observed_at < :oldest")
              .param("oldest", ts(now.minusSeconds(172800)))
              .update();
          jdbc.sql("DELETE FROM app.daily_video_view WHERE day < :oldest")
              .param("oldest", LocalDate.ofInstant(now, ZoneId.of("Asia/Seoul")).minusDays(29))
              .update();
          jdbc.sql("UPDATE app.collection_target SET view_count = NULL WHERE observed_at < :oldest")
              .param("oldest", ts(now.minusSeconds(172800)))
              .update();
          boolean expired =
              jdbc.sql(
                      """
          SELECT EXISTS (SELECT 1 FROM app.published_video_view p
            JOIN app.catalog_state s ON s.view_publication_id = p.publication_id
            WHERE p.observed_at < :oldest)
          """)
                  .param("oldest", ts(now.minusSeconds(30L * 86400)))
                  .query(Boolean.class)
                  .single();
          if (expired) {
            UUID replacement = UUID.randomUUID();
            jdbc.sql("INSERT INTO app.view_publication(id, published_at) VALUES (:id, :now)")
                .param("id", replacement)
                .param("now", ts(now))
                .update();
            jdbc.sql(
                    """
            INSERT INTO app.published_video_view(publication_id, video_id, view_count, observed_at)
            SELECT :id, p.video_id, p.view_count, p.observed_at FROM app.published_video_view p
              JOIN app.catalog_state s ON s.view_publication_id = p.publication_id
            WHERE p.observed_at >= :oldest
            """)
                .param("id", replacement)
                .param("oldest", ts(now.minusSeconds(30L * 86400)))
                .update();
            jdbc.sql("UPDATE app.catalog_state SET view_publication_id = :id WHERE singleton")
                .param("id", replacement)
                .update();
            jdbc.sql("DELETE FROM app.published_video_view WHERE publication_id <> :id")
                .param("id", replacement)
                .update();
            jdbc.sql("DELETE FROM app.view_publication WHERE id <> :id")
                .param("id", replacement)
                .update();
          }
          jdbc.sql("DELETE FROM app.collection_run WHERE logical_slot < :oldest")
              .param("oldest", ts(now.minusSeconds(30L * 86400)))
              .update();
          jdbc.sql(
                  """
          UPDATE app.video SET source_title = '', source_published_at = NULL, source_thumbnail_url = NULL,
            source_duration_seconds = NULL, source_observed_at = NULL
          WHERE source_observed_at < :oldest
          """)
              .param("oldest", ts(now.minusSeconds(30L * 86400)))
              .update();
          jdbc.sql(
                  "UPDATE app.video SET availability = 'UNAVAILABLE', embeddable = false, status_observed_at = NULL WHERE status_observed_at < :oldest")
              .param("oldest", ts(now.minusSeconds(30L * 86400)))
              .update();
          return null;
        });
  }

  private <T> T guarded(UUID token, Supplier<T> operation) {
    return transactions.execute(
        status -> {
          jdbc.sql("SET LOCAL statement_timeout = '10s'").update();
          UUID owner =
              jdbc.sql("SELECT owner_token FROM app.collection_control WHERE singleton FOR UPDATE")
                  .query((rs, n) -> rs.getObject(1, UUID.class))
                  .single();
          if (!token.equals(owner)) throw new CollectionFailure("LOCK_LOST", false);
          return operation.get();
        });
  }

  private Timestamp ts(Instant time) {
    return time == null ? null : Timestamp.from(time);
  }
}
