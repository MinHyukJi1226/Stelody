package com.stelody.review.youtube;

import com.stelody.catalog.domain.SearchText;
import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.domain.*;
import com.stelody.collector.youtube.YouTubeApiClient;
import com.stelody.review.domain.OriginalCredits;
import com.stelody.review.domain.OriginalCredits.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Best-effort metadata lookup for explicitly selected covers, separate from scheduled collection.
 */
@Component
public class YouTubeOriginalLookup {
  public record Target(
      UUID id,
      String videoId,
      String channelId,
      List<String> titles,
      List<String> knownArtists,
      String knownOriginalId) {}

  public record Outcome(Evidence evidence, boolean ambiguous) {
    public static Outcome found(Evidence evidence) {
      return new Outcome(evidence, false);
    }

    public static Outcome conflict() {
      return new Outcome(null, true);
    }
  }

  private final CollectorSettings settings;

  @Autowired
  public YouTubeOriginalLookup(Environment environment) {
    this(
        new CollectorSettings(
            false,
            "",
            "",
            "",
            environment.getProperty("stelody.collector.api-key", ""),
            URI.create("https://www.googleapis.com/youtube/v3"),
            Duration.ofSeconds(2),
            Duration.ofSeconds(3),
            Duration.ofSeconds(20),
            1,
            Duration.ZERO));
  }

  public YouTubeOriginalLookup(CollectorSettings settings) {
    this.settings = settings;
  }

  public Map<UUID, Outcome> find(List<Target> targets) {
    if (targets.isEmpty() || settings.apiKey().isBlank()) return Map.of();
    var session = new Lookup(settings);
    var sources = session.videos(targets.stream().map(Target::videoId).distinct().toList());
    var result = new HashMap<UUID, Outcome>();
    for (var target : targets) {
      var source = sources.get(target.videoId());
      if (source == null || !source.channelId().equals(target.channelId())) continue;
      Hint hint = OriginalCredits.hint(source.description());
      if (hint.conflicting()) {
        result.put(target.id(), Outcome.conflict());
        continue;
      }
      if (hint.videoId() != null) {
        if (hint.videoId().equals(target.knownOriginalId()) && !target.titles().isEmpty()) {
          result.put(
              target.id(),
              Outcome.found(
                  new Evidence(
                      target.titles().getFirst(),
                      List.of(),
                      hint.videoId(),
                      OriginalCredits.url(source.id()),
                      "DESCRIPTION")));
          continue;
        }
        var original = session.videos(List.of(hint.videoId())).get(hint.videoId());
        if (original != null)
          OriginalCredits.direct(original, hint, source.id(), target.titles())
              .ifPresent(e -> result.put(target.id(), Outcome.found(e)));
        result.putIfAbsent(target.id(), Outcome.conflict());
        // An explicit original reference must not be replaced by a different search result.
        continue;
      }
      if (target.knownOriginalId() != null
          && !target.titles().isEmpty()
          && !hint.artists().isEmpty()
          && hint.artists().stream()
              .allMatch(
                  a ->
                      target.knownArtists().stream()
                          .anyMatch(
                              b -> SearchText.searchKey(a).equals(SearchText.searchKey(b))))) {
        result.put(
            target.id(),
            Outcome.found(
                new Evidence(
                    target.titles().getFirst(),
                    hint.artists(),
                    target.knownOriginalId(),
                    OriginalCredits.url(source.id()),
                    "DESCRIPTION")));
        continue;
      }
      var expectedArtists = hint.artists();
      var titles = target.titles();
      if (titles.isEmpty()) continue;
      String queryTitle =
          titles.stream()
              .filter(t -> t.matches(".*[\\p{IsHiragana}\\p{IsKatakana}].*"))
              .reduce((a, b) -> b)
              .orElse(titles.getFirst());
      String query =
          queryTitle
              + (expectedArtists.isEmpty() ? "" : " " + expectedArtists.getFirst())
              + " original";
      var candidates = new ArrayList<Evidence>();
      for (var video : session.videos(session.search(query)).values()) {
        if (video.id().equals(source.id()) || video.channelId().equals(source.channelId()))
          continue;
        OriginalCredits.searched(video, titles, expectedArtists).ifPresent(candidates::add);
      }
      var evidence = OriginalCredits.unique(candidates);
      if (evidence.isPresent()) result.put(target.id(), Outcome.found(evidence.get()));
      else if (!candidates.isEmpty()) result.put(target.id(), Outcome.conflict());
      else if (hint.explicitArtists())
        result.put(
            target.id(),
            Outcome.found(
                new Evidence(
                    titles.getFirst(),
                    hint.artists(),
                    null,
                    OriginalCredits.url(source.id()),
                    "DESCRIPTION")));
    }
    return Map.copyOf(result);
  }

  private static final class Lookup {
    final YouTubeApiClient api;
    final CollectionBudget budget;
    final Map<String, Video> cache = new LinkedHashMap<>();
    final Set<String> fetched = new HashSet<>();
    final Map<String, List<String>> searches = new HashMap<>();
    boolean unavailable;

    Lookup(CollectorSettings settings) {
      api = new YouTubeApiClient(settings, d -> {});
      budget = new CollectionBudget(settings.maxRuntime());
    }

    Map<String, Video> videos(List<String> ids) {
      if (ids.isEmpty()) return Map.of();
      var missing =
          ids.stream()
              .filter(id -> id.matches("[A-Za-z0-9_-]{11}") && !fetched.contains(id))
              .distinct()
              .toList();
      for (int start = 0; start < missing.size() && !unavailable; start += 50) {
        var batch = missing.subList(start, Math.min(start + 50, missing.size()));
        try {
          var root =
              api.get(
                  "videos",
                  Map.of(
                      "part",
                      "snippet,status",
                      "id",
                      String.join(",", batch),
                      "fields",
                      "kind,items(id,snippet(channelId,channelTitle,title,description),status/privacyStatus)"),
                  budget);
          if (!root.path("kind").asText().equals("youtube#videoListResponse")
              || !root.path("items").isArray()) invalid();
          var parsed = new HashMap<String, Video>();
          for (var item : root.path("items")) {
            String id = required(item, "id"),
                availability = required(item.path("status"), "privacyStatus");
            if (!batch.contains(id) || parsed.containsKey(id)) invalid();
            if (!Set.of("public", "unlisted", "private").contains(availability)) invalid();
            if (availability.equals("private")) continue;
            var snippet = item.path("snippet");
            String channel = required(snippet, "channelId"), title = required(snippet, "title");
            String description = snippet.has("description") ? required(snippet, "description") : "";
            if (!channel.matches("UC[A-Za-z0-9_-]{22}")
                || title.length() > 500
                || description.length() > 10000) invalid();
            parsed.put(
                id, new Video(id, channel, required(snippet, "channelTitle"), title, description));
          }
          cache.putAll(parsed);
          fetched.addAll(batch);
        } catch (CollectionFailure failure) {
          unavailable = true;
        }
      }
      var result = new LinkedHashMap<String, Video>();
      for (String id : ids) if (cache.containsKey(id)) result.put(id, cache.get(id));
      return result;
    }

    List<String> search(String query) {
      if (unavailable) return List.of();
      if (searches.containsKey(query)) return searches.get(query);
      try {
        var root =
            api.get(
                "search",
                Map.of(
                    "part",
                    "snippet",
                    "type",
                    "video",
                    "maxResults",
                    "5",
                    "q",
                    query,
                    "fields",
                    "kind,items(id/videoId)"),
                budget);
        if (!root.path("kind").asText().equals("youtube#searchListResponse")
            || !root.path("items").isArray()
            || root.path("items").size() > 5) invalid();
        var ids = new LinkedHashSet<String>();
        for (var item : root.path("items")) {
          String id = required(item.path("id"), "videoId");
          if (!id.matches("[A-Za-z0-9_-]{11}") || !ids.add(id)) invalid();
        }
        var result = List.copyOf(ids);
        searches.put(query, result);
        return result;
      } catch (CollectionFailure failure) {
        unavailable = true;
        return List.of();
      }
    }

    static String required(JsonNode node, String key) {
      var value = node.path(key);
      if (!value.isString()) invalid();
      return value.asText();
    }

    static void invalid() {
      throw new CollectionFailure("INVALID_RESPONSE", false);
    }
  }
}
