package com.stelody.collector.repository;

import com.stelody.collector.domain.CollectionFailure;
import com.stelody.collector.domain.DiscoveryRules.Decision;
import com.stelody.collector.domain.VideoObservation;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class DiscoveryRepository {
  public record Channel(UUID id, String youtubeId) {}

  public record State(
      String uploads,
      String head,
      String pendingHead,
      String token,
      boolean inProgress,
      String backfillToken,
      boolean backfillComplete,
      Instant lastScanned) {}

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

  public DiscoveryRepository(DataSource source) {
    jdbc = JdbcClient.create(source);
    transactions = new TransactionTemplate(new JdbcTransactionManager(source));
    transactions.setTimeout(10);
  }

  public List<Channel> channels(UUID channel) {
    return jdbc.sql(
            "SELECT id, youtube_id FROM app.channel WHERE collection_enabled AND channel_type IN ('GROUP','MEMBER') "
                + (channel == null ? "" : "AND id=:channel ")
                + "ORDER BY id")
        .params(channel == null ? Map.of() : Map.of("channel", channel))
        .query((rs, n) -> new Channel(rs.getObject(1, UUID.class), rs.getString(2)))
        .list();
  }

  public UUID begin(UUID token, UUID channel, boolean backfill, Instant now) {
    return transactions.execute(
        status -> {
          jdbc.sql("SET LOCAL statement_timeout='10s'").update();
          jdbc.sql("UPDATE app.collection_control SET owner_token=:token WHERE singleton")
              .param("token", token)
              .update();
          jdbc.sql(
                  "UPDATE app.discovery_run SET status='FAILED',error_code='INTERRUPTED',finished_at=:now WHERE status='RUNNING'")
              .param("now", ts(now))
              .update();
          UUID id = UUID.randomUUID();
          jdbc.sql(
                  "INSERT INTO app.discovery_run(id,mode,channel_id,started_at,status) VALUES(:id,:mode,:channel,:now,'RUNNING')")
              .param("id", id)
              .param("mode", backfill ? "BACKFILL" : "NEW")
              .param("channel", channel)
              .param("now", ts(now))
              .update();
          return id;
        });
  }

  public State state(UUID token, Channel channel, String uploads) {
    return transactions.execute(
        status -> {
          fence(token);
          allowed(channel);
          jdbc.sql(
                  """
          INSERT INTO app.discovery_channel_state(channel_id,uploads_id) VALUES(:id,:uploads)
          ON CONFLICT(channel_id) DO UPDATE SET uploads_id=EXCLUDED.uploads_id,
            page_token=NULL,pending_head_id=NULL,in_progress=false,backfill_token=NULL,backfill_complete=false
          WHERE discovery_channel_state.uploads_id<>EXCLUDED.uploads_id
          """)
              .param("id", channel.id())
              .param("uploads", uploads)
              .update();
          return readState(channel.id());
        });
  }

  private State readState(UUID channel) {
    return jdbc.sql("SELECT * FROM app.discovery_channel_state WHERE channel_id=:id")
        .param("id", channel)
        .query(
            (rs, n) ->
                new State(
                    rs.getString("uploads_id"),
                    rs.getString("head_video_id"),
                    rs.getString("pending_head_id"),
                    rs.getString("page_token"),
                    rs.getBoolean("in_progress"),
                    rs.getString("backfill_token"),
                    rs.getBoolean("backfill_complete"),
                    rs.getTimestamp("last_scanned_at") == null
                        ? null
                        : rs.getTimestamp("last_scanned_at").toInstant()))
        .single();
  }

  public List<String> needed(List<String> ids) {
    if (ids.isEmpty()) return List.of();
    var skip =
        jdbc.sql(
                """
        SELECT youtube_id FROM app.video WHERE youtube_id IN (:ids)
        UNION SELECT youtube_id FROM app.review_item WHERE youtube_id IN (:ids) AND review_status='IGNORED'
        """)
            .param("ids", ids)
            .query(String.class)
            .list();
    return ids.stream().filter(id -> !skip.contains(id)).toList();
  }

  public State savePage(
      UUID token,
      UUID run,
      Channel channel,
      State before,
      Map<String, VideoObservation> observations,
      Map<String, Decision> decisions,
      String head,
      String next,
      boolean complete,
      boolean bootstrap,
      boolean backfill,
      Instant now) {
    return transactions.execute(
        status -> {
          fence(token);
          allowed(channel);
          State actual = readState(channel.id());
          if (!actual.equals(before)) throw new CollectionFailure("CHECKPOINT_CHANGED", false);
          int count = save(channel, observations, decisions, now);
          if (backfill) {
            jdbc.sql(
                    "UPDATE app.discovery_channel_state SET backfill_token=:next,backfill_complete=:done WHERE channel_id=:id")
                .param("next", next)
                .param("done", complete)
                .param("id", channel.id())
                .update();
          } else {
            jdbc.sql(
                    """
            UPDATE app.discovery_channel_state SET head_video_id=:head,
              pending_head_id=:pending,page_token=:next,in_progress=:active,last_scanned_at=:scanned,
              backfill_token=:history,backfill_complete=:historyDone WHERE channel_id=:id
            """)
                .param("head", complete ? head : before.head())
                .param("pending", complete ? null : head)
                .param("next", complete ? null : next)
                .param("active", !complete)
                .param("scanned", ts(complete ? now : before.lastScanned()))
                .param("history", bootstrap ? next : before.backfillToken())
                .param("historyDone", bootstrap ? next == null : before.backfillComplete())
                .param("id", channel.id())
                .update();
          }
          jdbc.sql(
                  "UPDATE app.discovery_run SET pages=pages+1,candidates=candidates+:count WHERE id=:id")
              .param("count", count)
              .param("id", run)
              .update();
          return readState(channel.id());
        });
  }

  private int save(
      Channel channel,
      Map<String, VideoObservation> observations,
      Map<String, Decision> decisions,
      Instant now) {
    for (var video : observations.values()) {
      if (video.channelId() != null && !video.channelId().equals(channel.youtubeId()))
        throw new CollectionFailure("CHANNEL_MISMATCH", false);
      if (!decisions.containsKey(video.youtubeId()))
        throw new CollectionFailure("INVALID_RESPONSE", false);
    }
    int changed = 0;
    for (var video : observations.values()) {
      var decision = decisions.get(video.youtubeId());
      changed +=
          jdbc.sql(
                  """
          INSERT INTO app.review_item(id,youtube_id,channel_id,source_title,source_published_at,
            source_thumbnail_url,source_duration_seconds,source_observed_at,availability,disposition,
            suggested_type,rule_version,decision_reason,first_seen_at)
          SELECT :id,:youtube,:channel,:title,:published,:thumb,:duration,:now,:availability,
            :disposition,:type,:rule,:reason,:now
          WHERE NOT EXISTS(SELECT 1 FROM app.video WHERE youtube_id=:youtube)
          ON CONFLICT(youtube_id) DO UPDATE SET source_title=EXCLUDED.source_title,
            source_published_at=EXCLUDED.source_published_at,source_thumbnail_url=EXCLUDED.source_thumbnail_url,
            source_duration_seconds=EXCLUDED.source_duration_seconds,source_observed_at=EXCLUDED.source_observed_at,
            availability=EXCLUDED.availability,disposition=EXCLUDED.disposition,suggested_type=EXCLUDED.suggested_type,
            rule_version=EXCLUDED.rule_version,decision_reason=EXCLUDED.decision_reason,version=review_item.version+1
          WHERE review_item.channel_id=EXCLUDED.channel_id AND review_item.review_status='PENDING'
            AND review_item.source_observed_at<=EXCLUDED.source_observed_at
          """)
              .param("id", UUID.randomUUID())
              .param("youtube", video.youtubeId())
              .param("channel", channel.id())
              .param("title", video.title())
              .param("published", ts(video.publishedAt()))
              .param("thumb", video.thumbnailUrl())
              .param("duration", video.durationSeconds())
              .param("now", ts(now))
              .param("availability", video.availability())
              .param("disposition", decision.disposition())
              .param("type", decision.suggestedType())
              .param("rule", decision.version())
              .param("reason", decision.reason())
              .update();
    }
    return changed;
  }

  public List<String> deferred(Channel channel, Instant oldestCheck) {
    return jdbc.sql(
            """
        SELECT youtube_id FROM app.review_item WHERE channel_id=:channel AND review_status='PENDING'
          AND disposition='DEFERRED' AND source_observed_at<:cutoff ORDER BY source_observed_at,id LIMIT 50
        """)
        .param("channel", channel.id())
        .param("cutoff", ts(oldestCheck))
        .query(String.class)
        .list();
  }

  public void refresh(
      UUID token,
      Channel channel,
      Map<String, VideoObservation> values,
      Map<String, Decision> decisions,
      Instant now) {
    transactions.executeWithoutResult(
        status -> {
          fence(token);
          allowed(channel);
          save(channel, values, decisions, now);
        });
  }

  public void resetPage(UUID token, Channel channel, boolean backfill) {
    transactions.executeWithoutResult(
        status -> {
          fence(token);
          allowed(channel);
          jdbc.sql(
                  backfill
                      ? "UPDATE app.discovery_channel_state SET backfill_token=NULL,backfill_complete=false WHERE channel_id=:id"
                      : "UPDATE app.discovery_channel_state SET page_token=NULL,pending_head_id=NULL,in_progress=false WHERE channel_id=:id")
              .param("id", channel.id())
              .update();
        });
  }

  public void finish(UUID token, UUID run, String state, String error, Instant now) {
    transactions.executeWithoutResult(
        status -> {
          fence(token);
          jdbc.sql(
                  "UPDATE app.discovery_run SET status=:status,error_code=:error,finished_at=:now WHERE id=:id")
              .param("status", state)
              .param("error", error)
              .param("now", ts(now))
              .param("id", run)
              .update();
        });
  }

  public void cleanup(UUID token, Instant now) {
    transactions.executeWithoutResult(
        status -> {
          fence(token);
          jdbc.sql(
                  """
          UPDATE app.review_item SET source_title=NULL,source_published_at=NULL,source_thumbnail_url=NULL,
            source_duration_seconds=NULL,availability='UNAVAILABLE',disposition='DEFERRED',suggested_type='UNKNOWN',
            decision_reason='SOURCE_EXPIRED',version=version+1
          WHERE source_observed_at<:cutoff AND decision_reason<>'SOURCE_EXPIRED'
          """)
              .param("cutoff", ts(now.minusSeconds(2592000)))
              .update();
          jdbc.sql("DELETE FROM app.discovery_run WHERE finished_at<:cutoff")
              .param("cutoff", ts(now.minusSeconds(2592000)))
              .update();
        });
  }

  private void allowed(Channel channel) {
    boolean allowed =
        jdbc.sql(
                "SELECT collection_enabled AND channel_type IN ('GROUP','MEMBER') AND youtube_id=:youtube FROM app.channel WHERE id=:id")
            .param("youtube", channel.youtubeId())
            .param("id", channel.id())
            .query(Boolean.class)
            .optional()
            .orElse(false);
    if (!allowed) throw new CollectionFailure("CHANNEL_NOT_ALLOWED", false);
  }

  private void fence(UUID token) {
    jdbc.sql("SET LOCAL statement_timeout='10s'").update();
    UUID actual =
        jdbc.sql("SELECT owner_token FROM app.collection_control WHERE singleton FOR UPDATE")
            .query(UUID.class)
            .single();
    if (!token.equals(actual)) throw new CollectionFailure("LOCK_LOST", false);
  }

  private static Timestamp ts(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }
}
