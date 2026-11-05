package com.stelody.milestone;

import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.domain.CollectionBudget;
import com.stelody.collector.domain.CollectionFailure;
import com.stelody.collector.repository.CollectionRepository;
import com.stelody.collector.service.CollectionLock;
import com.stelody.collector.youtube.YouTubeVideoClient;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class MilestoneWorker implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(MilestoneWorker.class);
  private final HikariDataSource source;
  private final MilestoneRepository repository;
  private final CollectorSettings settings;
  private final YouTubeVideoClient youtube;
  private final Clock clock;

  public MilestoneWorker(
      HikariDataSource source,
      CollectorSettings settings,
      Clock clock,
      CollectionBudget.Sleeper sleeper) {
    this.source = source;
    this.settings = settings;
    this.clock = clock;
    repository = new MilestoneRepository(source);
    youtube = new YouTubeVideoClient(settings, sleeper);
  }

  @Scheduled(scheduler = "milestoneTaskScheduler", initialDelay = 60000, fixedDelay = 60000)
  public void tick() {
    if (!settings.enabled() || Math.floorMod(clock.instant().getEpochSecond(), 3600) < 60) return;
    try (var lock = CollectionLock.acquire(source)) {
      if (lock == null) return;
      new CollectionRepository(source).checkPrivileges();
      repository.cleanup(clock.instant());
      for (var observation : repository.published(clock.instant())) repository.observe(observation);
      List<MilestoneRepository.Target> targets = repository.due(clock.instant());
      if (targets.isEmpty()) return;
      // One batch per minute, at most 50 videos, sharing the normal collector lock.
      var observations =
          youtube.fetch(
              targets.stream().map(MilestoneRepository.Target::youtubeId).toList(),
              new CollectionBudget(Duration.ofSeconds(20)));
      for (var t : targets) {
        var o = observations.get(t.youtubeId());
        if (o == null || (o.channelId() != null && !o.channelId().equals(t.channelId())))
          throw new CollectionFailure("INVALID_RESPONSE", false);
      }
      for (var t : targets) {
        var o = observations.get(t.youtubeId());
        if (!"PUBLIC".equals(o.availability()) || o.viewCount() == null)
          repository.unavailable(t, clock.instant());
        else repository.observe(repository.polled(t, o.viewCount(), clock.instant()));
      }
    } catch (Exception e) {
      String code = e instanceof CollectionFailure f ? f.code() : "MILESTONE_COLLECTION_FAILED";
      // Outbound/SQL exceptions may expose credentials. Persist only a fixed safe code.
      try {
        repository.failure(code, clock.instant());
      } catch (RuntimeException ignored) {
      }
      LOG.error("milestone status=FAILED code={}", code);
    }
  }

  @Override
  public void close() {
    source.close();
  }
}
