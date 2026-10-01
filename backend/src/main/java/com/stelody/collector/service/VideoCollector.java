package com.stelody.collector.service;

import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.domain.CollectionBudget;
import com.stelody.collector.domain.CollectionFailure;
import com.stelody.collector.repository.CollectionRepository;
import com.stelody.collector.youtube.YouTubeVideoClient;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import javax.sql.DataSource;

public final class VideoCollector {
  public record Result(UUID runId, String status, String code) {
    public int exitCode() {
      return status.equals("SUCCEEDED") || status.startsWith("SKIPPED") ? 0 : 1;
    }
  }

  private final DataSource source;
  private final CollectionRepository repository;
  private final YouTubeVideoClient youtube;
  private final CollectorSettings settings;
  private final Clock clock;

  public VideoCollector(
      DataSource source,
      CollectorSettings settings,
      Clock clock,
      CollectionBudget.Sleeper sleeper) {
    this.source = source;
    this.repository = new CollectionRepository(source);
    this.youtube = new YouTubeVideoClient(settings, sleeper);
    this.settings = settings;
    this.clock = clock;
  }

  public Result collect() {
    if (!settings.enabled()) return new Result(null, "SKIPPED_DISABLED", null);
    UUID token = UUID.randomUUID();
    CollectionRepository.Run run = null;
    var budget = new CollectionBudget(settings.maxRuntime());
    try {
      settings.validate();
      repository.checkPrivileges();
      try (var lock = CollectionLock.acquire(source)) {
        if (lock == null) return new Result(null, "SKIPPED_LOCKED", null);
        var now = clock.instant();
        run = repository.begin(token, now.truncatedTo(ChronoUnit.HOURS), now);
        repository.cleanup(token, now);
        if (run.completed()) return new Result(run.id(), "SKIPPED_COMPLETED", null);
        try {
          while (true) {
            budget.remaining();
            var pending = repository.pending(run.id());
            if (pending.isEmpty()) break;
            var observations =
                youtube.fetch(
                    pending.stream().map(CollectionRepository.Target::youtubeId).toList(), budget);
            budget.remaining();
            repository.save(token, run, pending, observations, clock.instant());
          }
          budget.remaining();
          repository.publish(token, run, clock.instant());
          return new Result(run.id(), "SUCCEEDED", null);
        } catch (CollectionFailure failure) {
          repository.fail(token, run.id(), failure.code(), clock.instant());
          return new Result(
              run.id(),
              failure.code().equals("QUOTA_EXHAUSTED")
                  ? "QUOTA_EXHAUSTED"
                  : failure.code().equals("TIME_LIMIT") ? "TIMED_OUT" : "FAILED",
              failure.code());
        } catch (RuntimeException exception) {
          repository.fail(token, run.id(), "DATABASE_ERROR", clock.instant());
          return new Result(run.id(), "FAILED", "DATABASE_ERROR");
        }
      }
    } catch (Exception exception) {
      return new Result(
          run == null ? null : run.id(),
          "FAILED",
          exception instanceof CollectionFailure failure
              ? failure.code()
              : "COLLECTOR_SETUP_FAILED");
    }
  }
}
