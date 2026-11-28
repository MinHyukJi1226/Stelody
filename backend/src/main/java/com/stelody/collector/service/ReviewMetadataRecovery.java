package com.stelody.collector.service;

import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.domain.CollectionBudget;
import com.stelody.collector.domain.CollectionFailure;
import com.stelody.collector.youtube.YouTubeVideoClient;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/** Rehydrates existing review identities only; never classifies or registers a song. */
public final class ReviewMetadataRecovery implements AutoCloseable {
  private record Target(UUID id, String youtube, String channel, long version) {}

  private final DataSource source;
  private final JdbcClient jdbc;
  private final YouTubeVideoClient videos;
  private final Clock clock;

  public ReviewMetadataRecovery(
      DataSource source,
      CollectorSettings settings,
      Clock clock,
      CollectionBudget.Sleeper sleeper) {
    this.source = source;
    this.jdbc = JdbcClient.create(source);
    this.videos = new YouTubeVideoClient(settings, sleeper);
    this.clock = clock;
  }

  @Scheduled(scheduler = "collectorTaskScheduler", initialDelay = 180000, fixedDelay = 3600000)
  public void tick() {
    try {
      refresh();
    } catch (Exception error) {
      LoggerFactory.getLogger(ReviewMetadataRecovery.class)
          .warn("Review cache recovery failed; will retry without changing review decisions");
    }
  }

  public void refresh() throws java.sql.SQLException {
    try (var lock = CollectionLock.acquire(source)) {
      if (lock == null) return;
      var targets =
          jdbc.sql(
                  """
          SELECT r.id,r.youtube_id,c.youtube_id,r.version FROM app.review_item r
          JOIN app.channel c ON c.id=r.channel_id
          WHERE r.review_status<>'REGISTERED' AND r.source_title IS NULL
            AND r.source_published_at IS NULL AND r.source_thumbnail_url IS NULL
            AND r.source_duration_seconds IS NULL AND r.availability IN ('PUBLIC','UNLISTED')
          ORDER BY r.source_observed_at,r.id LIMIT 50
          """)
              .query(
                  (r, n) ->
                      new Target(
                          r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getLong(4)))
              .list();
      if (targets.isEmpty()) return;
      var budget = new CollectionBudget(Duration.ofSeconds(20));
      var observations = videos.fetch(targets.stream().map(Target::youtube).toList(), budget);
      budget.remaining();
      lock.check();
      for (var target : targets) {
        var value = observations.get(target.youtube());
        if (value == null
            || (value.channelId() != null && !target.channel().equals(value.channelId())))
          throw new CollectionFailure("CHANNEL_MISMATCH", false);
      }
      for (var target : targets) {
        var value = observations.get(target.youtube());
        jdbc.sql(
                """
            UPDATE app.review_item r SET source_title=:title,source_published_at=:published,
              source_thumbnail_url=:thumbnail,source_duration_seconds=:duration,
              source_observed_at=CASE WHEN CAST(:title AS text) IS NULL THEN source_observed_at ELSE :observed END,
              availability=:availability,version=version+1
            WHERE id=:id AND youtube_id=:youtube AND version=:version AND review_status<>'REGISTERED'
              AND source_title IS NULL AND source_published_at IS NULL AND source_thumbnail_url IS NULL
              AND source_duration_seconds IS NULL
              AND EXISTS(SELECT 1 FROM app.channel c WHERE c.id=r.channel_id AND c.youtube_id=:channel)
            """)
            .param("title", value.title())
            .param(
                "published",
                value.publishedAt() == null ? null : Timestamp.from(value.publishedAt()))
            .param("thumbnail", value.thumbnailUrl())
            .param("duration", value.durationSeconds())
            .param("observed", Timestamp.from(clock.instant()))
            .param("availability", value.availability())
            .param("id", target.id())
            .param("youtube", target.youtube())
            .param("version", target.version())
            .param("channel", target.channel())
            .update();
      }
    }
  }

  @Override
  public void close() throws Exception {
    if (source instanceof AutoCloseable closeable) closeable.close();
  }
}
