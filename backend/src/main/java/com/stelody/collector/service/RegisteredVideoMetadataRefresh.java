package com.stelody.collector.service;

import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.domain.CollectionBudget;
import com.stelody.collector.domain.CollectionFailure;
import com.stelody.collector.repository.RegisteredVideoMetadataRepository;
import com.stelody.collector.youtube.YouTubeVideoClient;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class RegisteredVideoMetadataRefresh implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(RegisteredVideoMetadataRefresh.class);
  private final DataSource source;
  private final RegisteredVideoMetadataRepository repository;
  private final YouTubeVideoClient videos;
  private final Clock clock;

  public RegisteredVideoMetadataRefresh(
      DataSource source,
      CollectorSettings settings,
      Clock clock,
      CollectionBudget.Sleeper sleeper) {
    this.source = source;
    repository = new RegisteredVideoMetadataRepository(source);
    videos = new YouTubeVideoClient(settings, sleeper);
    this.clock = clock;
  }

  @Scheduled(scheduler = "collectorTaskScheduler", initialDelay = 120000, fixedDelay = 3600000)
  public void tick() {
    try {
      refresh();
    } catch (Exception error) {
      LOG.warn(
          "Registered video metadata refresh failed code={}",
          error instanceof CollectionFailure failure ? failure.code() : "METADATA_REFRESH_FAILED");
    }
  }

  public void refresh() throws java.sql.SQLException {
    try (var lock = CollectionLock.acquire(source)) {
      if (lock == null) return;
      var targets = repository.due(clock.instant());
      if (targets.isEmpty()) return;
      var budget = new CollectionBudget(Duration.ofSeconds(20));
      var observations =
          videos.fetch(
              targets.stream().map(RegisteredVideoMetadataRepository.Target::youtubeId).toList(),
              budget);
      budget.remaining();
      lock.check();
      repository.save(targets, observations, clock.instant());
    }
  }

  @Override
  public void close() throws Exception {
    if (source instanceof AutoCloseable closeable) closeable.close();
  }
}
