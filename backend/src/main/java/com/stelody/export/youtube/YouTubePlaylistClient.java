package com.stelody.export.youtube;

import com.stelody.export.config.*;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import tools.jackson.databind.*;

@Component
public class YouTubePlaylistClient {
  public static class Failure extends RuntimeException {
    private final String code;
    private final boolean rejected;
    private final int status;

    public Failure(String code, boolean rejected) {
      this(code, rejected, 0);
    }

    private Failure(String code, boolean rejected, int status) {
      super(code);
      this.code = code;
      this.rejected = rejected;
      this.status = status;
    }

    public String code() {
      return code;
    }

    public boolean rejected() {
      return rejected;
    }
  }

  private final RestClient http;
  private final ObjectMapper mapper;

  public YouTubePlaylistClient(ExportSettings settings, ObjectMapper mapper) {
    http = ExportHttp.builder().baseUrl(settings.apiUrl()).build();
    this.mapper = mapper;
  }

  public static String marker(UUID job) {
    return "Stelody export " + job;
  }

  public String create(String token, String name, UUID job) {
    var response =
        call(
            true,
            () ->
                http.post()
                    .uri("/playlists?part=snippet,status")
                    .headers(h -> h.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        Map.of(
                            "snippet",
                            Map.of("title", name, "description", marker(job)),
                            "status",
                            Map.of("privacyStatus", "private")))
                    .retrieve()
                    .body(JsonNode.class));
    String id = response.path("id").asText();
    if (!id.matches("[A-Za-z0-9_-]{1,100}")) throw new Failure("YOUTUBE_INVALID_RESPONSE", false);
    return id;
  }

  public void append(String token, String playlist, String video, int position) {
    var response =
        call(
            true,
            () ->
                http.post()
                    .uri("/playlistItems?part=snippet")
                    .headers(h -> h.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        Map.of(
                            "snippet",
                            Map.of(
                                "playlistId",
                                playlist,
                                "position",
                                position,
                                "resourceId",
                                Map.of("kind", "youtube#video", "videoId", video))))
                    .retrieve()
                    .body(JsonNode.class));
    if (response.path("id").asText().isBlank())
      throw new Failure("YOUTUBE_INVALID_RESPONSE", false);
  }

  public Set<String> publicVideos(String token, List<String> requested) {
    if (requested.isEmpty()) return Set.of();
    var response =
        call(
            false,
            () ->
                http.get()
                    .uri(
                        b ->
                            b.path("/videos")
                                .queryParam("part", "status")
                                .queryParam("id", String.join(",", requested))
                                .build())
                    .headers(h -> h.setBearerAuth(token))
                    .retrieve()
                    .body(JsonNode.class));
    if (!response.path("items").isArray()) throw new Failure("YOUTUBE_INVALID_RESPONSE", false);
    Set<String> result = new HashSet<>();
    for (var item : response.path("items")) {
      String id = item.path("id").asText();
      if (requested.contains(id)
          && "public".equals(item.path("status").path("privacyStatus").asText())
          && "processed".equals(item.path("status").path("uploadStatus").asText())) result.add(id);
    }
    return Set.copyOf(result);
  }

  public List<String> videos(String token, String playlist) {
    var videos = new ArrayList<String>();
    String page = null;
    for (int i = 0; i < 11; i++) {
      String cursor = page;
      var response =
          readPlaylistItems(
              cursor == null,
              () ->
                  http.get()
                      .uri(
                          b -> {
                            b.path("/playlistItems")
                                .queryParam("part", "snippet")
                                .queryParam("playlistId", playlist)
                                .queryParam("maxResults", 50);
                            if (cursor != null) b.queryParam("pageToken", cursor);
                            return b.build();
                          })
                      .headers(h -> h.setBearerAuth(token))
                      .retrieve()
                      .body(JsonNode.class));
      if (!response.path("items").isArray()) throw new Failure("YOUTUBE_INVALID_RESPONSE", false);
      for (var item : response.path("items")) {
        var snippet = item.path("snippet");
        if (snippet.path("position").asInt(-1) != videos.size())
          throw new Failure("REMOTE_PLAYLIST_CHANGED", false);
        videos.add(snippet.path("resourceId").path("videoId").asText());
      }
      page = response.path("nextPageToken").asString(null);
      if (page == null) return List.copyOf(videos);
    }
    throw new Failure("REMOTE_PLAYLIST_CHANGED", false);
  }

  public Optional<String> findCreated(String token, UUID job) {
    String page = null, found = null;
    for (int i = 0; i < 20; i++) {
      String cursor = page;
      var response =
          call(
              false,
              () ->
                  http.get()
                      .uri(
                          b -> {
                            b.path("/playlists")
                                .queryParam("part", "snippet,status")
                                .queryParam("mine", true)
                                .queryParam("maxResults", 50);
                            if (cursor != null) b.queryParam("pageToken", cursor);
                            return b.build();
                          })
                      .headers(h -> h.setBearerAuth(token))
                      .retrieve()
                      .body(JsonNode.class));
      if (!response.path("items").isArray()) throw new Failure("YOUTUBE_INVALID_RESPONSE", false);
      for (var item : response.path("items"))
        if (marker(job).equals(item.path("snippet").path("description").asText())) {
          String id = item.path("id").asText();
          if (found != null
              || !id.matches("[A-Za-z0-9_-]{1,100}")
              || !item.path("status").path("privacyStatus").asText().equals("private"))
            throw new Failure("REMOTE_PLAYLIST_CHANGED", false);
          found = id;
        }
      page = response.path("nextPageToken").asString(null);
      if (page == null) return Optional.ofNullable(found);
    }
    throw new Failure("YOUTUBE_RESULT_UNCONFIRMED", false);
  }

  private JsonNode readPlaylistItems(
      boolean firstPage, java.util.function.Supplier<JsonNode> action) {
    for (int attempt = 0; ; attempt++) {
      try {
        return call(false, action);
      } catch (Failure e) {
        // A newly created playlist can be visible in playlists.list before playlistItems.list.
        // Retry only confirmed 404 reads; never interpret missing data as an empty playlist.
        if (!firstPage
            || e.status != 404
            || !e.code().equals("REMOTE_PLAYLIST_MISSING")
            || attempt == 2) throw e;
        try {
          Thread.sleep((attempt + 1) * 1000L);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new Failure("YOUTUBE_RESPONSE_UNCERTAIN", false);
        }
      }
    }
  }

  private JsonNode call(boolean mutation, java.util.function.Supplier<JsonNode> action) {
    try {
      var value = action.get();
      if (value == null) throw new Failure("YOUTUBE_INVALID_RESPONSE", false);
      return value;
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      String reason = "";
      try {
        reason =
            mapper
                .readTree(e.getResponseBodyAsString())
                .path("error")
                .path("errors")
                .path(0)
                .path("reason")
                .asText();
      } catch (RuntimeException ignored) {
      }
      String code =
          switch (reason) {
            case "quotaExceeded", "dailyLimitExceeded" -> "YOUTUBE_QUOTA_EXCEEDED";
            case "videoNotFound" -> "YOUTUBE_VIDEO_UNAVAILABLE";
            case "playlistNotFound" -> "REMOTE_PLAYLIST_MISSING";
            case "youtubeSignupRequired" -> "YOUTUBE_CHANNEL_REQUIRED";
            case "insufficientPermissions", "authError" -> "YOUTUBE_RECONNECT_REQUIRED";
            default ->
                status == 401
                    ? "YOUTUBE_RECONNECT_REQUIRED"
                    : status == 429 ? "YOUTUBE_RATE_LIMITED" : "YOUTUBE_REQUEST_FAILED";
          };
      throw new Failure(code, mutation && status >= 400 && status < 500, status);
    } catch (RestClientException e) {
      throw new Failure("YOUTUBE_RESPONSE_UNCERTAIN", false);
    }
  }
}
