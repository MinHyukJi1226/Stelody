package com.stelody.collector.scheduling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class ScheduledCollector implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(ScheduledCollector.class);
  private final Clock clock;
  private final Function<Instant, CollectionScheduleState.Plan> state;
  private final Function<CollectionScheduleState.Plan, Integer> execution;
  private final AutoCloseable resource;
  private Instant retryAfter = Instant.MIN;

  public ScheduledCollector(
      Clock clock,
      Function<Instant, CollectionScheduleState.Plan> state,
      Function<CollectionScheduleState.Plan, Integer> execution,
      AutoCloseable resource) {
    this.clock = clock;
    this.state = state;
    this.execution = execution;
    this.resource = resource;
  }

  @Scheduled(scheduler = "collectorTaskScheduler", fixedDelay = 60000, initialDelay = 60000)
  public synchronized void tick() {
    if (clock.instant().isBefore(retryAfter)) return;
    try {
      var plan = state.apply(clock.instant());
      if (!plan.due()) return;
      if (execution.apply(plan) != 0) {
        retryAfter = clock.instant().plus(Duration.ofMinutes(15));
        LOG.error("scheduled collector status=FAILED code=COLLECTION_FAILED");
      }
    } catch (RuntimeException error) {
      retryAfter = clock.instant().plus(Duration.ofMinutes(15));
      // Raw database / outbound exceptions can contain credentials.
      LOG.error("scheduled collector status=FAILED code=SCHEDULE_CHECK_FAILED");
    }
  }

  @Override
  public void close() throws Exception {
    resource.close();
  }
}
