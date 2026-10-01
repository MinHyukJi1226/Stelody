package com.stelody.collector.youtube;

import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.domain.CollectionBudget;
import com.stelody.collector.domain.CollectionFailure;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public final class YouTubeUploadsClient {
  public record Page(List<String> videoIds, String nextToken) {}

  private final YouTubeApiClient api;

  public YouTubeUploadsClient(CollectorSettings settings, CollectionBudget.Sleeper sleeper) {
    api = new YouTubeApiClient(settings, sleeper);
  }

  public String uploads(String channelId, CollectionBudget budget) {
    if (!channelId.matches("UC[A-Za-z0-9_-]{22}"))
      throw new IllegalArgumentException("Invalid channel");
    var root = api.get("channels", Map.of("part", "contentDetails", "id", channelId), budget);
    var items = items(root, "youtube#channelListResponse");
    if (items.size() == 0) throw new CollectionFailure("CHANNEL_UNAVAILABLE", false);
    if (items.size() != 1 || !channelId.equals(text(items.get(0), "id"))) invalid();
    String id = text(items.get(0).path("contentDetails").path("relatedPlaylists"), "uploads");
    if (!id.matches("[A-Za-z0-9_-]{10,150}")) invalid();
    return id;
  }

  public Page page(String channelId, String playlistId, String token, CollectionBudget budget) {
    if (!channelId.matches("UC[A-Za-z0-9_-]{22}") || !playlistId.matches("[A-Za-z0-9_-]{10,150}"))
      throw new IllegalArgumentException("Invalid uploads list");
    var parameters = new HashMap<String, String>();
    parameters.put("part", "snippet,contentDetails");
    parameters.put("playlistId", playlistId);
    parameters.put("maxResults", "50");
    if (token != null) parameters.put("pageToken", token);
    var root = api.get("playlistItems", parameters, budget);
    var items = items(root, "youtube#playlistItemListResponse");
    if (items.size() > 50) invalid();
    String next = optionalText(root, "nextPageToken");
    if (next != null && (next.isBlank() || next.length() > 2048 || next.equals(token))) invalid();
    if (items.size() == 0 && next != null) invalid();
    var ids = new ArrayList<String>();
    for (var item : items) {
      var snippet = item.path("snippet");
      if (!playlistId.equals(text(snippet, "playlistId"))
          || !channelId.equals(text(snippet, "channelId"))
          || !"youtube#video".equals(text(snippet.path("resourceId"), "kind"))) invalid();
      String id = text(snippet.path("resourceId"), "videoId");
      if (!id.matches("[A-Za-z0-9_-]{11}") || ids.contains(id)) invalid();
      String detailId = optionalText(item.path("contentDetails"), "videoId");
      String ownerId = optionalText(snippet, "videoOwnerChannelId");
      if ((detailId != null && !detailId.equals(id))
          || (ownerId != null && !ownerId.equals(channelId))) invalid();
      ids.add(id);
    }
    return new Page(List.copyOf(ids), next);
  }

  private JsonNode items(JsonNode root, String kind) {
    if (!kind.equals(text(root, "kind")) || !root.path("items").isArray()) invalid();
    return root.path("items");
  }

  private String text(JsonNode node, String name) {
    String value = optionalText(node, name);
    if (value == null) invalid();
    return value;
  }

  private String optionalText(JsonNode node, String name) {
    var value = node.path(name);
    if (value.isNull() || value.isMissingNode()) return null;
    if (!value.isString()) invalid();
    return value.asText();
  }

  private void invalid() {
    throw new CollectionFailure("INVALID_RESPONSE", false);
  }
}
