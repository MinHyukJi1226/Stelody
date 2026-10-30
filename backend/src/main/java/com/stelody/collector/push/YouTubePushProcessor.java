package com.stelody.collector.push;

import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.domain.*;
import com.stelody.collector.repository.*;
import com.stelody.collector.service.CollectionLock;
import com.stelody.collector.youtube.YouTubeVideoClient;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

public final class YouTubePushProcessor implements AutoCloseable {
  private final DataSource source;
  private final DiscoveryRepository repository;
  private final YouTubeVideoClient videos;
  private final Clock clock;
  private final boolean classify;

  public YouTubePushProcessor(
      DataSource source,
      CollectorSettings settings,
      Clock clock,
      boolean classify,
      boolean autoPublication,
      CollectionBudget.Sleeper sleeper) {
    this.source = source;
    this.clock = clock;
    this.classify = classify;
    repository = new DiscoveryRepository(source, classify && autoPublication);
    videos = new YouTubeVideoClient(settings, sleeper);
  }

  public List<DiscoveryRepository.Channel> channels() {
    return repository.channels(null);
  }

  public void receive(String youtubeChannel, Map<String, Instant> updates) {
    if (updates.isEmpty()) return;
    if (updates.size() > 50
        || updates.keySet().stream().anyMatch(id -> !id.matches("[A-Za-z0-9_-]{11}")))
      throw new IllegalArgumentException("INVALID_VIDEO_BATCH");
    try (var lock = CollectionLock.acquire(source)) {
      if (lock == null) throw new CollectionFailure("COLLECTOR_BUSY", true);
      var channel =
          channels().stream()
              .filter(c -> c.youtubeId().equals(youtubeChannel))
              .findFirst()
              .orElseThrow(() -> new CollectionFailure("CHANNEL_NOT_ALLOWED", false));
      var needed = repository.neededNotifications(updates);
      if (needed.isEmpty()) return;
      new CollectionRepository(source).checkPrivileges();
      var budget = new CollectionBudget(Duration.ofSeconds(20));
      var values = videos.fetch(needed, budget);
      // Upload notifications can arrive before the Data API exposes the new video. Ask the hub
      // to retry rather than acknowledging and losing it. Daily discovery remains the fallback.
      boolean notReady =
          values.values().stream().anyMatch(v -> v.channelId() == null || v.title() == null);
      values = new HashMap<>(values);
      values.values().removeIf(v -> v.channelId() == null || v.title() == null);
      if (values.isEmpty()) throw new CollectionFailure("VIDEO_NOT_READY", true);
      var rules =
          classify
              ? new CollectionRuleStore(JdbcClient.create(source), new ObjectMapper())
                  .current()
                  .rules()
              : new DiscoveryRules();
      repository.checkPublicationPrivileges();
      var decisions = new HashMap<String, DiscoveryRules.Decision>();
      values.forEach((id, v) -> decisions.put(id, rules.decide(v, classify)));
      budget.remaining();
      lock.check();
      repository.saveNotification(UUID.randomUUID(), channel, values, decisions, clock.instant());
      if (notReady) throw new CollectionFailure("VIDEO_NOT_READY", true);
    } catch (java.sql.SQLException e) {
      throw new CollectionFailure("DATABASE_ERROR", true);
    }
  }

  @Override
  public void close() throws Exception {
    if (source instanceof AutoCloseable c) c.close();
  }
}
