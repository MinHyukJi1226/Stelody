package com.stelody.collector.service;

import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.domain.CollectionBudget;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;

public final class PremiereMonitor implements AutoCloseable {
  private final HikariDataSource source;
  private final PublicScheduleRefresh refresh;
  private final boolean enabled;

  public PremiereMonitor(
      CollectorSettings settings, boolean enabled, boolean classify, boolean autoPublish) {
    this.enabled = enabled;
    source = CollectorExecution.createDataSource(settings);
    refresh =
        new PublicScheduleRefresh(
            source,
            settings,
            Clock.systemUTC(),
            duration -> Thread.sleep(duration),
            classify,
            autoPublish);
  }

  @Scheduled(scheduler = "collectorTaskScheduler", fixedDelay = 60000, initialDelay = 60000)
  public void tick() {
    if (enabled) refresh.refresh(new CollectionBudget(Duration.ofSeconds(20)));
  }

  @Override
  public void close() {
    source.close();
  }
}
