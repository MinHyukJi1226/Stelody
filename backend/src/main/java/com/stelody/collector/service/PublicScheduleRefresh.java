package com.stelody.collector.service;

import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.domain.CollectionBudget;
import com.stelody.collector.domain.CollectionFailure;
import com.stelody.collector.youtube.YouTubeVideoClient;
import com.stelody.schedule.PublicScheduleRepository;
import java.time.Clock;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class PublicScheduleRefresh {
  private static final Logger LOG = LoggerFactory.getLogger(PublicScheduleRefresh.class);
  private final DataSource source;
  private final PublicScheduleRepository repository;
  private final YouTubeVideoClient videos;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public PublicScheduleRefresh(
      DataSource source,
      CollectorSettings settings,
      Clock clock,
      CollectionBudget.Sleeper sleeper) {
    this.source = source;
    this.clock = clock;
    repository = new PublicScheduleRepository(JdbcClient.create(source));
    videos = new YouTubeVideoClient(settings, sleeper);
    transactions = new TransactionTemplate(new JdbcTransactionManager(source));
    transactions.setTimeout(10);
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
      transactions.executeWithoutResult(
          tx -> {
            for (var target : targets) {
              repository.observe(
                  target.channelId(),
                  target.youtubeChannelId(),
                  observations.get(target.youtubeId()),
                  observedAt);
            }
          });
    } catch (Exception error) {
      LOG.warn(
          "public schedule refresh status=FAILED code={}",
          error instanceof CollectionFailure failure ? failure.code() : "SCHEDULE_REFRESH_FAILED");
    }
  }
}
