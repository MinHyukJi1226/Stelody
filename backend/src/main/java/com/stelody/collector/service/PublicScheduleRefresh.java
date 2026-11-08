package com.stelody.collector.service;

import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.domain.CollectionBudget;
import com.stelody.collector.domain.CollectionFailure;
import com.stelody.collector.domain.DiscoveryRules;
import com.stelody.collector.repository.CollectionRuleStore;
import com.stelody.collector.repository.DiscoveryRepository;
import com.stelody.collector.youtube.YouTubeVideoClient;
import com.stelody.schedule.PublicScheduleRepository;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

public final class PublicScheduleRefresh {
  private static final Logger LOG = LoggerFactory.getLogger(PublicScheduleRefresh.class);
  private final DataSource source;
  private final PublicScheduleRepository repository;
  private final YouTubeVideoClient videos;
  private final Clock clock;
  private final DiscoveryRepository discovery;
  private final boolean classify;

  public PublicScheduleRefresh(
      DataSource source,
      CollectorSettings settings,
      Clock clock,
      CollectionBudget.Sleeper sleeper) {
    this(source, settings, clock, sleeper, true, false);
  }

  public PublicScheduleRefresh(
      DataSource source,
      CollectorSettings settings,
      Clock clock,
      CollectionBudget.Sleeper sleeper,
      boolean classify,
      boolean autoPublish) {
    this.source = source;
    this.classify = classify;
    discovery = new DiscoveryRepository(source, false, classify && autoPublish);
    this.clock = clock;
    repository = new PublicScheduleRepository(JdbcClient.create(source));
    videos = new YouTubeVideoClient(settings, sleeper);
  }

  public void refresh(CollectionBudget budget) {
    try (var lock = CollectionLock.acquire(source)) {
      if (lock == null) return;
      var now = clock.instant();
      repository.cleanup(now);
      var targets = repository.due(now);
      if (targets.isEmpty()) return;
      var observations =
          videos.fetch(
              targets.stream().map(PublicScheduleRepository.Target::youtubeId).toList(), budget);
      var observedAt = clock.instant();
      lock.check();
      var rules =
          classify
              ? new CollectionRuleStore(JdbcClient.create(source), new ObjectMapper())
                  .current()
                  .rules()
              : new DiscoveryRules();
      for (var target : targets) {
        var video = observations.get(target.youtubeId());
        discovery.saveNotification(
            UUID.randomUUID(),
            new DiscoveryRepository.Channel(target.channelId(), target.youtubeChannelId()),
            Map.of(video.youtubeId(), video),
            Map.of(video.youtubeId(), rules.decide(video, classify)),
            observedAt);
      }
    } catch (Exception error) {
      LOG.warn(
          "public schedule refresh status=FAILED code={}",
          error instanceof CollectionFailure failure ? failure.code() : "SCHEDULE_REFRESH_FAILED");
    }
  }
}
