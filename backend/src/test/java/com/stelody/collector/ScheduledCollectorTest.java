package com.stelody.collector;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.stelody.collector.config.CollectorSchedulingConfiguration;
import com.stelody.collector.scheduling.CollectionScheduleState;
import com.stelody.collector.scheduling.CollectionScheduleState.Plan;
import com.stelody.collector.scheduling.ScheduledCollector;
import com.stelody.export.config.ExportScheduling;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ScheduledCollectorTest {
  @Test
  void discoveryUsesKoreanMidnightAcrossUtcDates() {
    assertThat(CollectionScheduleState.discoveryBoundary(Instant.parse("2026-10-09T14:59:59Z")))
        .isEqualTo(Instant.parse("2026-10-08T15:00:00Z"));
    assertThat(CollectionScheduleState.discoveryBoundary(Instant.parse("2026-10-09T15:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-09T15:00:00Z"));
    assertThat(CollectionScheduleState.discoveryBoundary(Instant.parse("2026-10-10T03:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-09T15:00:00Z"));
  }

  @Test
  void regularTriggerIsHourlyWithoutMinutePolling() throws Exception {
    var schedule = ScheduledCollector.class.getMethod("tick").getAnnotation(Scheduled.class);
    assertThat(schedule.cron()).isEqualTo("0 0 * * * *");
    assertThat(schedule.zone()).isEqualTo("Asia/Seoul");
    assertThat(schedule.fixedDelay()).isEqualTo(-1);
  }

  @Test
  void startupChecksOnceAndCompleteWorkCreatesNoFollowUp() {
    var now = Instant.parse("2026-10-09T00:00:00Z");
    var scheduler = mock(TaskScheduler.class);
    var collector =
        new ScheduledCollector(
            Clock.fixed(now, ZoneOffset.UTC),
            time -> new Plan(false, false, false),
            plan -> 0,
            scheduler,
            () -> {});
    collector.onReady();
    verify(scheduler).schedule(any(Runnable.class), eq(now.plusSeconds(60)));
    collector.tick();
    verifyNoMoreInteractions(scheduler);
  }

  @Test
  void unfinishedWorkSchedulesOneContinuationAndShutdownCancelsIt() throws Exception {
    var now = Instant.parse("2026-10-09T00:00:00Z");
    var scheduler = mock(TaskScheduler.class);
    ScheduledFuture<?> future = mock(ScheduledFuture.class);
    doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
    var collector =
        new ScheduledCollector(
            Clock.fixed(now, ZoneOffset.UTC),
            time -> new Plan(true, false, false),
            plan -> 0,
            scheduler,
            () -> {});
    collector.tick();
    verify(scheduler).schedule(any(Runnable.class), eq(now.plusSeconds(900)));
    collector.close();
    verify(future).cancel(false);
    collector.tick();
    collector.onReady();
    verifyNoMoreInteractions(scheduler);
  }

  @Test
  void persistedStateIsCheckedAgainInsteadOfAssumingExitZeroMeansCurrentSlotCompleted() {
    var calls = new AtomicInteger();
    var collector =
        new ScheduledCollector(
            Clock.systemUTC(),
            now -> new Plan(calls.get() < 2, false, false),
            plan -> {
              calls.incrementAndGet();
              return 0;
            },
            mock(TaskScheduler.class),
            () -> {});
    collector.tick(); // May have completed an older interrupted slot / admin retry.
    collector.tick(); // Current slot is still due.
    collector.tick();
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void failuresBackOffForFifteenMinutesAndDoNotKillFutureInvocations() {
    var clock = mock(Clock.class);
    var now = Instant.parse("2026-10-09T00:17:00Z");
    when(clock.instant()).thenReturn(now);
    var calls = new AtomicInteger();
    var collector =
        new ScheduledCollector(
            clock,
            time -> new Plan(true, false, false),
            plan -> {
              calls.incrementAndGet();
              throw new IllegalStateException("private-error");
            },
            mock(TaskScheduler.class),
            () -> {});
    collector.tick();
    when(clock.instant()).thenReturn(now.plusSeconds(899));
    collector.tick();
    assertThat(calls.get()).isEqualTo(1);
    when(clock.instant()).thenReturn(now.plusSeconds(900));
    collector.tick();
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void nonzeroExecutionAlsoBacksOffAndQueuedRetriesRunWithoutNewCollection() {
    var clock = mock(Clock.class);
    when(clock.instant()).thenReturn(Instant.parse("2026-10-09T00:05:00Z"));
    var calls = new AtomicInteger();
    var collector =
        new ScheduledCollector(
            clock,
            time -> new Plan(false, false, true),
            plan -> {
              assertThat(plan.videos()).isFalse();
              calls.incrementAndGet();
              return 1;
            },
            mock(TaskScheduler.class),
            () -> {});
    collector.tick();
    collector.tick();
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void disablingEitherFlagCreatesNoCollectorPoolOrSchedulerAndCliProfileIsExcluded() {
    var runner =
        new ApplicationContextRunner()
            .withUserConfiguration(CollectorSchedulingConfiguration.class);
    runner.run(context -> assertThat(context).doesNotHaveBean(ScheduledCollector.class));
    runner
        .withPropertyValues("stelody.collector.scheduled-enabled=true")
        .run(context -> assertThat(context).doesNotHaveBean(ScheduledCollector.class));
    runner
        .withPropertyValues("stelody.collector.enabled=true")
        .run(context -> assertThat(context).doesNotHaveBean(ScheduledCollector.class));
    runner
        .withPropertyValues(
            "spring.profiles.active=collector",
            "stelody.collector.enabled=true",
            "stelody.collector.scheduled-enabled=true")
        .run(context -> assertThat(context).doesNotHaveBean(ScheduledCollector.class));
  }

  @Test
  void enablingCollectionPreservesDefaultSchedulerAndDoesNotReplaceWebDataSource() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
        .withUserConfiguration(CollectorSchedulingConfiguration.class, ExportScheduling.class)
        .withBean("webDataSource", DataSource.class, () -> mock(DataSource.class))
        .withPropertyValues(
            "stelody.collector.enabled=true",
            "stelody.collector.scheduled-enabled=true",
            "stelody.collector.db-url=jdbc:postgresql://localhost:1/test",
            "stelody.collector.db-username=collector",
            "stelody.collector.db-password=test",
            "stelody.collector.api-key=test")
        .run(
            context -> {
              assertThat(context).hasSingleBean(DataSource.class);
              assertThat(context).hasBean("taskScheduler").hasBean("collectorTaskScheduler");
              assertThat(context.getBean("taskScheduler"))
                  .isNotSameAs(context.getBean("collectorTaskScheduler"));
              assertThat(
                      context
                          .getBean("collectorTaskScheduler", ThreadPoolTaskScheduler.class)
                          .getThreadNamePrefix())
                  .isEqualTo("collector-");
            });
  }

  @Test
  void ownedPoolIsClosedOnShutdown() throws Exception {
    var pool = mock(AutoCloseable.class);
    new ScheduledCollector(
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            time -> new Plan(false, false, false),
            plan -> 0,
            mock(TaskScheduler.class),
            pool)
        .close();
    verify(pool).close();
  }
}
