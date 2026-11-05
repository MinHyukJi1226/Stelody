package com.stelody.milestone;

import com.stelody.catalog.repository.PublicCatalogSql;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class MilestoneRepository {
  public record Observation(
      UUID videoId,
      String youtubeId,
      String channelId,
      long views,
      Instant at,
      Long previousViews,
      Instant previousAt) {}

  public record Target(UUID videoId, String youtubeId, String channelId) {}

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

  public MilestoneRepository(DataSource source) {
    jdbc = JdbcClient.create(source);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    transactions.setTimeout(10);
  }

  public List<Observation> published(Instant now) {
    return jdbc.sql(
            PublicCatalogSql.SONGS
                + """
      SELECT v.id,v.youtube_id,c.youtube_id AS channel_id,p.view_count,p.observed_at,
        b.view_count AS previous_views,b.observed_at AS previous_at
      FROM public_songs s JOIN app.video v ON v.id=s.representative_video_id
      JOIN app.channel c ON c.id=v.channel_id AND c.collection_enabled AND c.channel_type IN('MEMBER','GROUP')
      JOIN app.catalog_state cs ON cs.singleton
      JOIN app.published_video_view p ON p.publication_id=cs.view_publication_id AND p.video_id=v.id
      LEFT JOIN app.view_milestone_state m ON m.video_id=v.id
      LEFT JOIN LATERAL (SELECT view_count,observed_at FROM app.view_snapshot b
        WHERE b.video_id=v.id AND b.observed_at<=p.observed_at-interval '1 hour'
          AND b.observed_at>=p.observed_at-interval '6 hours'
        ORDER BY b.observed_at DESC LIMIT 1) b ON true
      WHERE p.observed_at>=:oldest AND p.observed_at<=:now
        AND (m.video_id IS NULL OR p.observed_at>m.observed_at)
        AND EXISTS(SELECT 1 FROM app.collection_run r WHERE r.publication_id=p.publication_id AND r.status='SUCCEEDED')
      """)
        .param("oldest", ts(now.minusSeconds(7200)))
        .param("now", ts(now))
        .query(
            (r, n) ->
                new Observation(
                    r.getObject("id", UUID.class),
                    r.getString("youtube_id"),
                    r.getString("channel_id"),
                    r.getLong("view_count"),
                    r.getTimestamp("observed_at").toInstant(),
                    r.getObject("previous_views", Long.class),
                    instant(r.getTimestamp("previous_at"))))
        .list();
  }

  public void observe(Observation o) {
    transactions.executeWithoutResult(
        tx -> {
          boolean eligible =
              jdbc.sql(
                      PublicCatalogSql.SONGS
                          + """
            SELECT EXISTS(SELECT 1 FROM public_songs s JOIN app.video v ON v.id=s.representative_video_id
              JOIN app.channel c ON c.id=v.channel_id AND c.collection_enabled AND c.channel_type IN('MEMBER','GROUP')
              WHERE v.id=:id AND v.youtube_id=:youtube AND c.youtube_id=:channel)
            """)
                  .param("id", o.videoId())
                  .param("youtube", o.youtubeId())
                  .param("channel", o.channelId())
                  .query(Boolean.class)
                  .single();
          if (!eligible) return;
          var existing =
              jdbc.sql(
                      "SELECT view_count,observed_at,highest_target,flat_checks FROM app.view_milestone_state WHERE video_id=:id FOR UPDATE")
                  .param("id", o.videoId())
                  .query(
                      (r, n) ->
                          new State(
                              r.getLong(1),
                              r.getTimestamp(2).toInstant(),
                              r.getLong(3),
                              r.getInt(4)))
                  .optional();
          if (existing.isPresent() && !o.at().isAfter(existing.get().at())) return;
          var previous = existing.orElse(null);
          long reached = MilestonePolicy.reached(o.views());
          long highest = previous == null ? reached : Math.max(reached, previous.highest());
          Instant estimate =
              previous != null && o.views() <= previous.views()
                  ? null
                  : MilestonePolicy.estimate(o.views(), o.at(), o.previousViews(), o.previousAt());
          int flat =
              previous != null && o.views() == previous.views()
                  ? Math.min(2, previous.flat() + 1)
                  : 0;
          var interval =
              flat == 1
                  ? java.time.Duration.ofMinutes(10)
                  : MilestonePolicy.interval(o.at(), estimate);
          if (previous != null && reached > previous.highest()) {
            // At most the ten latest steps can appear on the homepage, including a large jump.
            jdbc.sql(
                    """
          INSERT INTO app.view_milestone_event(video_id,target,detected_at)
          SELECT :id,target,:at FROM generate_series(CAST(:low AS bigint),CAST(:high AS bigint),100000) target
          ON CONFLICT DO NOTHING
          """)
                .param("id", o.videoId())
                .param("at", ts(o.at()))
                .param(
                    "low",
                    Math.max(
                        previous.highest() + MilestonePolicy.STEP,
                        reached - 9 * MilestonePolicy.STEP))
                .param("high", reached)
                .update();
          }
          jdbc.sql(
                  """
        INSERT INTO app.view_milestone_state(video_id,view_count,observed_at,highest_target,estimated_at,next_check_at,available,flat_checks)
        VALUES(:id,:views,:at,:highest,:estimate,:next,true,:flat)
        ON CONFLICT(video_id) DO UPDATE SET view_count=EXCLUDED.view_count,observed_at=EXCLUDED.observed_at,
          highest_target=EXCLUDED.highest_target,estimated_at=EXCLUDED.estimated_at,next_check_at=EXCLUDED.next_check_at,available=true,flat_checks=EXCLUDED.flat_checks
        """)
              .param("id", o.videoId())
              .param("views", o.views())
              .param("at", ts(o.at()))
              .param("highest", highest)
              .param("estimate", ts(estimate))
              .param("next", ts(o.at().plus(interval)))
              .param("flat", flat)
              .update();
        });
  }

  private record State(long views, Instant at, long highest, int flat) {}

  public List<Target> due(Instant now) {
    return jdbc.sql(
            PublicCatalogSql.SONGS
                + """
      SELECT v.id,v.youtube_id,c.youtube_id AS channel_id FROM public_songs s
      JOIN app.video v ON v.id=s.representative_video_id
      JOIN app.channel c ON c.id=v.channel_id AND c.collection_enabled AND c.channel_type IN('MEMBER','GROUP')
      JOIN app.view_milestone_state m ON m.video_id=v.id
      WHERE m.available AND m.next_check_at<=:now AND m.observed_at>=:oldest AND m.observed_at<=:now
        AND m.view_count%100000>=95000 AND (m.view_count/100000)*100000>=m.highest_target
        AND NOT EXISTS(SELECT 1 FROM app.view_milestone_control WHERE retry_after>:now)
      ORDER BY m.next_check_at,v.id LIMIT 50
      """)
        .param("now", ts(now))
        .param("oldest", ts(now.minusSeconds(7200)))
        .query((r, n) -> new Target(r.getObject(1, UUID.class), r.getString(2), r.getString(3)))
        .list();
  }

  public Observation polled(Target t, long views, Instant at) {
    return jdbc.sql(
            """
      SELECT view_count,observed_at FROM app.view_snapshot WHERE video_id=:id
        AND observed_at<=:baseline AND observed_at>=:oldest ORDER BY observed_at DESC LIMIT 1
      """)
        .param("id", t.videoId())
        .param("baseline", ts(at.minusSeconds(3600)))
        .param("oldest", ts(at.minusSeconds(21600)))
        .query(
            (r, n) ->
                new Observation(
                    t.videoId(),
                    t.youtubeId(),
                    t.channelId(),
                    views,
                    at,
                    r.getLong(1),
                    r.getTimestamp(2).toInstant()))
        .optional()
        .orElse(new Observation(t.videoId(), t.youtubeId(), t.channelId(), views, at, null, null));
  }

  public void unavailable(Target t, Instant now) {
    jdbc.sql(
            "UPDATE app.view_milestone_state SET available=false,estimated_at=NULL,next_check_at=:next WHERE video_id=:id AND observed_at<:now")
        .param("now", ts(now))
        .param("next", ts(now.plusSeconds(3600)))
        .param("id", t.videoId())
        .update();
  }

  public void failure(String code, Instant now) {
    jdbc.sql(
            "INSERT INTO app.view_milestone_control(singleton,retry_after,last_error_code) VALUES(true,:next,:code) ON CONFLICT(singleton) DO UPDATE SET retry_after=EXCLUDED.retry_after,last_error_code=EXCLUDED.last_error_code")
        .param("next", ts(now.plusSeconds(code.equals("QUOTA_EXHAUSTED") ? 3600 : 900)))
        .param("code", code)
        .update();
  }

  public void cleanup(Instant now) {
    transactions.executeWithoutResult(
        tx -> {
          jdbc.sql("DELETE FROM app.view_milestone_event WHERE detected_at<:oldest")
              .param("oldest", ts(now.minusSeconds(86400)))
              .update();
          jdbc.sql("DELETE FROM app.view_milestone_state WHERE observed_at<:oldest")
              .param("oldest", ts(now.minusSeconds(30L * 86400)))
              .update();
        });
  }

  private static Timestamp ts(Instant at) {
    return at == null ? null : Timestamp.from(at);
  }

  private static Instant instant(Timestamp at) {
    return at == null ? null : at.toInstant();
  }
}
