package com.stelody.collector.youtube;

import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.domain.CollectionBudget;
import com.stelody.collector.domain.CollectionFailure;
import com.stelody.collector.domain.VideoObservation;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public final class YouTubeVideoClient {
  private final YouTubeApiClient api;

  public YouTubeVideoClient(CollectorSettings settings, CollectionBudget.Sleeper sleeper) {
    api = new YouTubeApiClient(settings, sleeper);
  }

  public Map<String, VideoObservation> fetch(List<String> ids, CollectionBudget budget) {
    if (ids.isEmpty()
        || ids.size() > 50
        || ids.stream().distinct().count() != ids.size()
        || ids.stream().anyMatch(id -> !id.matches("[A-Za-z0-9_-]{11}")))
      throw new IllegalArgumentException("Invalid video batch");
    return parse(
        ids,
        api.get(
            "videos",
            Map.of(
                "part",
                "snippet,contentDetails,status,statistics,liveStreamingDetails",
                "id",
                String.join(",", ids)),
            budget));
  }

  private Map<String, VideoObservation> parse(List<String> ids, JsonNode root) {
    try {
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
        // An absent part means no broadcast history. A malformed present part is not absence.
        var liveStreamingDetails = item.path("liveStreamingDetails");
        if (!liveStreamingDetails.isMissingNode() && !liveStreamingDetails.isObject()) invalid();
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
                publicVideo ? views : null,
                optionalText(snippet, "liveBroadcastContent"),
                !liveStreamingDetails.isMissingNode()));
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
