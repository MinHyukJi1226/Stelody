package com.stelody.collector.youtube;

import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.domain.CollectionBudget;
import com.stelody.collector.domain.CollectionFailure;
import com.stelody.collector.domain.VideoObservation;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class YouTubeVideoClient {
  private static final int MAX_BODY = 2 * 1024 * 1024;
  private final CollectorSettings settings;
  private final CollectionBudget.Sleeper sleeper;
  private final ObjectMapper mapper = new ObjectMapper();

  public YouTubeVideoClient(CollectorSettings settings, CollectionBudget.Sleeper sleeper) {
    this.settings = settings;
    this.sleeper = sleeper;
  }

  public Map<String, VideoObservation> fetch(List<String> ids, CollectionBudget budget) {
    if (ids.isEmpty()
        || ids.size() > 50
        || ids.stream().distinct().count() != ids.size()
        || ids.stream().anyMatch(id -> !id.matches("[A-Za-z0-9_-]{11}")))
      throw new IllegalArgumentException("Invalid video batch");
    for (int attempt = 1; ; attempt++) {
      try {
        return request(ids, budget);
      } catch (CollectionFailure failure) {
        if (!failure.retryable() || attempt >= settings.maxAttempts()) throw failure;
        budget.pause(settings.retryDelay().multipliedBy(1L << (attempt - 1)), sleeper);
      }
    }
  }

  private Map<String, VideoObservation> request(List<String> ids, CollectionBudget budget) {
    Duration timeout =
        settings.readTimeout().compareTo(budget.remaining()) < 0
            ? settings.readTimeout()
            : budget.remaining();
    var http =
        HttpClient.newBuilder()
            .connectTimeout(
                settings.connectTimeout().compareTo(timeout) < 0
                    ? settings.connectTimeout()
                    : timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(timeout);
    try (http) {
      var rest =
          RestClient.builder()
              .baseUrl(settings.apiBase().toString())
              .requestFactory(factory)
              .build();
      return rest.get()
          .uri(
              builder ->
                  builder
                      .path("/videos")
                      .queryParam("part", "snippet,contentDetails,status,statistics")
                      .queryParam("id", String.join(",", ids))
                      .build())
          .header("X-Goog-Api-Key", settings.apiKey())
          .exchange(
              (request, response) -> {
                int status = response.getStatusCode().value();
                byte[] bytes = response.getBody().readNBytes(MAX_BODY + 1);
                budget.remaining();
                if (bytes.length > MAX_BODY) throw new CollectionFailure("INVALID_RESPONSE", false);
                if (status != 200) throw httpFailure(status, bytes);
                return parse(ids, bytes);
              });
    } catch (CollectionFailure failure) {
      throw failure;
    } catch (RestClientException exception) {
      throw new CollectionFailure("API_IO_ERROR", true);
    } catch (RuntimeException exception) {
      throw new CollectionFailure("INVALID_RESPONSE", false);
    }
  }

  private CollectionFailure httpFailure(int status, byte[] body) {
    try {
      var errors = mapper.readTree(body).path("error").path("errors");
      for (var error : errors) {
        String reason = error.path("reason").asText();
        if (reason.equals("quotaExceeded") || reason.equals("dailyLimitExceeded"))
          return new CollectionFailure("QUOTA_EXHAUSTED", false);
        if (reason.equals("rateLimitExceeded") || reason.equals("userRateLimitExceeded"))
          return new CollectionFailure("API_RATE_LIMIT", true);
      }
    } catch (RuntimeException ignored) {
      /* Only normalized status is retained. */
    }
    return new CollectionFailure("API_HTTP_" + status, status == 429 || status >= 500);
  }

  private Map<String, VideoObservation> parse(List<String> ids, byte[] body) {
    try {
      JsonNode root = mapper.readTree(body);
      if (!root.path("kind").asText().equals("youtube#videoListResponse")
          || !root.path("items").isArray()
          || root.has("nextPageToken")) invalid();
      if (root.has("error")) invalid();
      var total = root.path("pageInfo").path("totalResults");
      if (!total.isMissingNode()
          && (!total.isIntegralNumber() || total.asLong() != root.path("items").size())) invalid();
      var result = new HashMap<String, VideoObservation>();
      for (var item : root.path("items")) {
        String id = text(item, "id");
        if (!ids.contains(id) || result.containsKey(id)) invalid();
        var status = item.path("status");
        String availability =
            switch (text(status, "privacyStatus")) {
              case "public" -> "PUBLIC";
              case "unlisted" -> "UNLISTED";
              case "private" -> "PRIVATE";
              default -> throw new CollectionFailure("INVALID_RESPONSE", false);
            };
        String upload = status.path("uploadStatus").asText();
        if (upload.equals("deleted")) availability = "DELETED";
        else if (upload.equals("failed") || upload.equals("rejected")) availability = "UNAVAILABLE";
        var snippet = item.path("snippet");
        if (snippet.path("liveBroadcastContent").asText().equals("upcoming"))
          availability = "UNAVAILABLE";
        boolean publicVideo = availability.equals("PUBLIC") || availability.equals("UNLISTED");
        String channel = optionalText(snippet, "channelId");
        String title = optionalText(snippet, "title");
        Instant published = optionalInstant(snippet, "publishedAt");
        if (publicVideo
            && (channel == null || title == null || title.isBlank() || published == null))
          invalid();
        if (channel != null && !channel.matches("UC[A-Za-z0-9_-]{22}")) invalid();
        if (title != null && title.length() > 500) invalid();
        String thumb = thumbnail(snippet.path("thumbnails"));
        Long seconds = null;
        String duration = optionalText(item.path("contentDetails"), "duration");
        if (duration != null) {
          Duration parsed = Duration.parse(duration);
          if (parsed.isNegative()) invalid();
          seconds = parsed.getSeconds();
        }
        if (!status.path("embeddable").isBoolean()) invalid();
        Long views = optionalCount(item.path("statistics"), "viewCount");
        result.put(
            id,
            new VideoObservation(
                id,
                channel,
                availability,
                title,
                published,
                thumb,
                seconds,
                status.path("embeddable").asBoolean(),
                publicVideo ? views : null));
      }
      for (String id : ids) result.putIfAbsent(id, VideoObservation.unavailable(id));
      return Map.copyOf(result);
    } catch (CollectionFailure failure) {
      throw failure;
    } catch (RuntimeException exception) {
      throw new CollectionFailure("INVALID_RESPONSE", false);
    }
  }

  private String text(JsonNode node, String key) {
    String value = optionalText(node, key);
    if (value == null) invalid();
    return value;
  }

  private String optionalText(JsonNode node, String key) {
    var value = node.path(key);
    if (value.isMissingNode() || value.isNull()) return null;
    if (!value.isString()) invalid();
    return value.asText();
  }

  private Instant optionalInstant(JsonNode node, String key) {
    String value = optionalText(node, key);
    if (value == null) return null;
    Instant instant = Instant.parse(value);
    if (instant.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
        || !instant.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))) invalid();
    return instant;
  }

  private Long optionalCount(JsonNode node, String key) {
    String value = optionalText(node, key);
    if (value == null) return null;
    if (!value.matches("[0-9]+")) invalid();
    return Long.parseLong(value);
  }

  private String thumbnail(JsonNode thumbnails) {
    for (String key : List.of("maxres", "standard", "high", "medium", "default")) {
      String url = optionalText(thumbnails.path(key), "url");
      if (url == null) continue;
      URI uri = URI.create(url);
      if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
        invalid();
      return url;
    }
    return null;
  }

  private void invalid() {
    throw new CollectionFailure("INVALID_RESPONSE", false);
  }
}
