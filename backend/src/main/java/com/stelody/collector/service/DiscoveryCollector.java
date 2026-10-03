package com.stelody.collector.service;

import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.config.DiscoveryOptions;
import com.stelody.collector.domain.CollectionBudget;
import com.stelody.collector.domain.CollectionFailure;
import com.stelody.collector.domain.DiscoveryRules;
import com.stelody.collector.domain.VideoObservation;
import com.stelody.collector.repository.CollectionRepository;
import com.stelody.collector.repository.CollectionRuleStore;
import com.stelody.collector.repository.DiscoveryRepository;
import com.stelody.collector.youtube.YouTubeUploadsClient;
import com.stelody.collector.youtube.YouTubeVideoClient;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

public final class DiscoveryCollector {
  private final DataSource source;
  private final CollectorSettings settings;
  private final DiscoveryOptions options;
  private final Clock clock;
  private final DiscoveryRepository repository;
  private final YouTubeUploadsClient uploads;
  private final YouTubeVideoClient videos;

  public DiscoveryCollector(
      DataSource source,
      CollectorSettings settings,
      DiscoveryOptions options,
      Clock clock,
      CollectionBudget.Sleeper sleeper) {
    this.source = source;
    this.settings = settings;
    this.options = options;
    this.clock = clock;
    repository = new DiscoveryRepository(source);
    uploads = new YouTubeUploadsClient(settings, sleeper);
    videos = new YouTubeVideoClient(settings, sleeper);
  }

  public VideoCollector.Result collect() {
    return collect(new CollectionBudget(settings.maxRuntime()));
  }

  public VideoCollector.Result collect(CollectionBudget budget) {
    return collect(budget, false, id -> {});
  }

  VideoCollector.Result retry(CollectionBudget budget, Consumer<UUID> started) {
    return collect(budget, true, started);
  }

  private VideoCollector.Result collect(
      CollectionBudget budget, boolean lockHeld, Consumer<UUID> started) {
    if (!settings.enabled()) return new VideoCollector.Result(null, "SKIPPED_DISABLED", null);
    UUID token = UUID.randomUUID(), run = null;
    try {
      settings.validate();
      new CollectionRepository(source).checkPrivileges();
      try (var lock = lockHeld ? null : CollectionLock.acquire(source)) {
        if (!lockHeld && lock == null)
          return new VideoCollector.Result(null, "SKIPPED_LOCKED", null);
        var channels = repository.channels(options.channelId());
        if (options.channelId() != null && channels.isEmpty())
          throw new CollectionFailure("CHANNEL_NOT_ALLOWED", false);
        run = repository.begin(token, options.channelId(), options.backfill(), clock.instant());
        started.accept(run);
        try {
          // Freeze one configuration for this entire run, even if an administrator edits it.
          // Classification-disabled deployments do not require the new collector read grant.
          var rules =
              options.classificationAllowed()
                  ? new CollectionRuleStore(JdbcClient.create(source), new ObjectMapper())
                      .current()
                      .rules()
                  : new DiscoveryRules();
          repository.ruleVersion(token, run, rules.version());
          repository.cleanup(token, clock.instant());
          for (var channel : channels) {
            budget.remaining();
            var state =
                repository.state(token, channel, uploads.uploads(channel.youtubeId(), budget));
            boolean bootstrap = state.head() == null && !state.inProgress();
            if (options.backfill() && bootstrap)
              throw new CollectionFailure("DISCOVERY_REQUIRED_FIRST", false);
            boolean scan =
                options.backfill()
                    ? !state.backfillComplete()
                    : state.inProgress()
                        || state.lastScanned() == null
                        || !state.lastScanned().isAfter(clock.instant().minusSeconds(7200));
            var tokens = new HashSet<String>();
            for (int page = 0; scan && page < options.maxPages(); page++) {
              budget.remaining();
              String cursor = options.backfill() ? state.backfillToken() : state.token();
              if (cursor != null && !tokens.add(cursor))
                throw new CollectionFailure("PAGE_TOKEN_CYCLE", false);
              YouTubeUploadsClient.Page response;
              try {
                response = uploads.page(channel.youtubeId(), state.uploads(), cursor, budget);
              } catch (CollectionFailure failure) {
                if (failure.code().equals("INVALID_PAGE_TOKEN"))
                  repository.resetPage(token, channel, options.backfill());
                throw failure;
              }
              String head =
                  state.inProgress()
                      ? state.pendingHead()
                      : response.videoIds().isEmpty()
                          ? state.head()
                          : response.videoIds().getFirst();
              int boundary =
                  options.backfill() || state.head() == null
                      ? -1
                      : response.videoIds().indexOf(state.head());
              List<String> ids =
                  boundary < 0 ? response.videoIds() : response.videoIds().subList(0, boundary);
              var needed = repository.needed(ids);
              Map<String, VideoObservation> observations =
                  needed.isEmpty() ? Map.of() : videos.fetch(needed, budget);
              boolean complete =
                  options.backfill()
                      ? response.nextToken() == null
                      : bootstrap || boundary >= 0 || response.nextToken() == null;
              budget.remaining();
              state =
                  repository.savePage(
                      token,
                      run,
                      channel,
                      state,
                      observations,
                      decisions(observations, rules),
                      head,
                      response.nextToken(),
                      complete,
                      bootstrap,
                      options.backfill(),
                      clock.instant());
              if (complete) break;
            }
            if (!options.backfill()) {
              var deferred =
                  repository.needed(
                      repository.deferred(channel, clock.instant().minusSeconds(7200)));
              if (!deferred.isEmpty()) {
                var observations = videos.fetch(deferred, budget);
                budget.remaining();
                repository.refresh(
                    token, channel, observations, decisions(observations, rules), clock.instant());
              }
            }
          }
          budget.remaining();
          repository.finish(token, run, "SUCCEEDED", null, clock.instant());
          return new VideoCollector.Result(run, "SUCCEEDED", null);
        } catch (RuntimeException error) {
          String code =
              error instanceof CollectionFailure failure ? failure.code() : "DATABASE_ERROR";
          String status =
              code.equals("QUOTA_EXHAUSTED")
                  ? "QUOTA_EXHAUSTED"
                  : code.equals("TIME_LIMIT") ? "TIMED_OUT" : "FAILED";
          repository.finish(token, run, status, code, clock.instant());
          return new VideoCollector.Result(run, status, code);
        }
      }
    } catch (Exception error) {
      return new VideoCollector.Result(
          run,
          "FAILED",
          error instanceof CollectionFailure failure ? failure.code() : "COLLECTOR_SETUP_FAILED");
    }
  }

  private Map<String, DiscoveryRules.Decision> decisions(
      Map<String, VideoObservation> values, DiscoveryRules rules) {
    var result = new HashMap<String, DiscoveryRules.Decision>();
    values.forEach(
        (id, value) -> result.put(id, rules.decide(value, options.classificationAllowed())));
    return result;
  }
}
