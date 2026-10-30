package com.stelody.collector.push;

import com.stelody.collector.repository.DiscoveryRepository.Channel;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class YouTubePushSubscriptions implements AutoCloseable {
  private final YouTubePushSettings settings;
  private final Supplier<List<Channel>> channels;
  private final Clock clock;
  private final URI hub;
  private final HttpClient http;
  private final Map<String, Instant> leases = new ConcurrentHashMap<>();
  private final Map<String, Instant> pending = new ConcurrentHashMap<>();

  public YouTubePushSubscriptions(
      YouTubePushSettings settings, Supplier<List<Channel>> channels, Clock clock, URI hub) {
    this.settings = settings;
    this.channels = channels;
    this.clock = clock;
    this.hub = hub;
    http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public boolean allowed(String channel, String token) {
    return settings.accepts(channel, token)
        && channels.get().stream().anyMatch(c -> c.youtubeId().equals(channel));
  }

  public String verify(
      String channel, String token, String mode, String topic, String challenge, Long seconds) {
    if (!allowed(channel, token)
        || !pending.getOrDefault(channel, Instant.MIN).isAfter(clock.instant())
        || !"subscribe".equals(mode)
        || !YouTubePushSettings.topic(channel).equals(topic)
        || challenge == null
        || challenge.isEmpty()
        || challenge.length() > 2048
        || challenge.chars().anyMatch(Character::isISOControl)
        || seconds == null
        || seconds < 1
        || seconds > 8640000)
      throw new IllegalArgumentException("INVALID_YOUTUBE_PUSH_VERIFICATION");
    leases.put(channel, clock.instant().plusSeconds(seconds));
    pending.remove(channel);
    return challenge;
  }

  public void denied(String channel, String topic) {
    if (!YouTubePushSettings.topic(channel).equals(topic))
      throw new IllegalArgumentException("INVALID_YOUTUBE_PUSH_VERIFICATION");
    leases.remove(channel);
    pending.remove(channel);
    LoggerFactory.getLogger(getClass())
        .warn("youtube push subscription status=FAILED code=HUB_SUBSCRIPTION_DENIED");
  }

  @Scheduled(scheduler = "youtubePushTaskScheduler", initialDelay = 60000, fixedDelay = 300000)
  public synchronized void renew() {
    try {
      var allowed = channels.get();
      var ids =
          allowed.stream().map(Channel::youtubeId).collect(java.util.stream.Collectors.toSet());
      leases.keySet().retainAll(ids);
      pending.keySet().retainAll(ids);
      for (var channel : allowed) {
        String id = channel.youtubeId();
        if (leases.getOrDefault(id, Instant.MIN).isAfter(clock.instant().plusSeconds(3600)))
          continue;
        if (pending.getOrDefault(id, Instant.MIN).isAfter(clock.instant())) continue;
        var values = new LinkedHashMap<String, String>();
        values.put("hub.mode", "subscribe");
        values.put("hub.topic", YouTubePushSettings.topic(id));
        values.put("hub.callback", settings.callback(id));
        values.put("hub.secret", settings.deliverySecret(id));
        values.put("hub.lease_seconds", "86400");
        values.put("hub.verify", "async");
        String form =
            values.entrySet().stream()
                .map(
                    e ->
                        URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                            + "="
                            + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
        try {
          pending.put(id, clock.instant().plusSeconds(300));
          var request =
              HttpRequest.newBuilder(hub)
                  .timeout(Duration.ofSeconds(10))
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(HttpRequest.BodyPublishers.ofString(form))
                  .build();
          var response = http.send(request, HttpResponse.BodyHandlers.discarding());
          if (response.statusCode() != 202 && response.statusCode() != 204)
            throw new IllegalStateException();
          // Verification, not the hub's acceptance response, establishes the lease.
        } catch (Exception e) {
          pending.remove(id);
          if (e instanceof InterruptedException) Thread.currentThread().interrupt();
          LoggerFactory.getLogger(getClass())
              .warn("youtube push subscription status=FAILED code=HUB_SUBSCRIPTION_FAILED");
          if (Thread.currentThread().isInterrupted()) return;
        }
      }
    } catch (RuntimeException e) {
      LoggerFactory.getLogger(getClass())
          .warn("youtube push subscription status=FAILED code=CHANNEL_LOOKUP_FAILED");
    }
  }

  @Override
  public void close() {
    http.close();
  }
}
