package com.stelody.collector.scheduling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;

public final class ScheduledCollector implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(ScheduledCollector.class);
  private final Clock clock;
  private final Function<Instant, CollectionScheduleState.Plan> state;
  private final Function<CollectionScheduleState.Plan, Integer> execution;
  private final AutoCloseable resource;
  private final TaskScheduler scheduler;
  private ScheduledFuture<?> pending;
  private boolean closed;
  private Instant retryAfter = Instant.MIN;

  public ScheduledCollector(
      Clock clock,
      Function<Instant, CollectionScheduleState.Plan> state,
      Function<CollectionScheduleState.Plan, Integer> execution,
      TaskScheduler scheduler,
      AutoCloseable resource) {
    this.clock = clock;
    this.state = state;
    this.execution = execution;
    this.scheduler = scheduler;
    this.resource = resource;
  }

  @EventListener(ApplicationReadyEvent.class)
  public synchronized void onReady() {
    if (!closed) followUp(Duration.ofMinutes(1));
  }

  @Scheduled(scheduler = "collectorTaskScheduler", cron = "0 0 * * * *", zone = "Asia/Seoul")
  public synchronized void tick() {
    if (closed) return;
    if (clock.instant().isBefore(retryAfter)) return;
    cancelPending();
    try {
      var plan = state.apply(clock.instant());
      if (!plan.due()) return;
      if (execution.apply(plan) != 0) {
        retryAfter = clock.instant().plus(Duration.ofMinutes(15));
        LOG.error("scheduled collector status=FAILED code=COLLECTION_FAILED");
        followUp(Duration.ofMinutes(15));
      } else if (state.apply(clock.instant()).due()) {
        // An older slot, lock contention or a page limit can leave work unfinished.
        followUp(Duration.ofMinutes(15));
      }
    } catch (RuntimeException error) {
      retryAfter = clock.instant().plus(Duration.ofMinutes(15));
      // Raw database / outbound exceptions can contain credentials.
      LOG.error("scheduled collector status=FAILED code=SCHEDULE_CHECK_FAILED");
      followUp(Duration.ofMinutes(15));
    }
  }

  private void followUp(Duration delay) {
    cancelPending();
    pending = scheduler.schedule(this::tick, clock.instant().plus(delay));
  }

  private void cancelPending() {
    if (pending != null) pending.cancel(false);
    pending = null;
  }

  @Override
  public synchronized void close() throws Exception {
    closed = true;
    cancelPending();
    resource.close();
  }
}
