package com.stelody.song.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SongDtos {
  private SongDtos() {}

  @Schema(requiredProperties = {"id", "name", "kind", "activityStatus"})
  public record Participant(
      UUID id, String name, String kind, @Schema(nullable = true) String activityStatus) {}

  @Schema(requiredProperties = {"id", "name"})
  public record Artist(UUID id, String name) {}

  @Schema(requiredProperties = {"id", "title", "artists"})
  public record Work(UUID id, String title, List<Artist> artists) {}

  @Schema(
      requiredProperties = {
        "id",
        "title",
        "type",
        "publishedAt",
        "thumbnailUrl",
        "participants",
        "work",
        "collaboration",
        "isSpecialEvent",
        "specialEventLabel",
        "viewCount",
        "viewsObservedAt"
      })
  public record Card(
      UUID id,
      String title,
      String type,
      Instant publishedAt,
      @Schema(nullable = true) String thumbnailUrl,
      List<Participant> participants,
      @Schema(nullable = true) Work work,
      boolean collaboration,
      boolean isSpecialEvent,
      @Schema(nullable = true) String specialEventLabel,
      @Schema(nullable = true) Long viewCount,
      @Schema(nullable = true) Instant viewsObservedAt) {}

  @Schema(requiredProperties = {"id", "youtubeId", "kind", "url", "embeddable"})
  public record Video(UUID id, String youtubeId, String kind, String url, boolean embeddable) {}

  @Schema(requiredProperties = {"visibility", "recommendedQuery", "checkedAt"})
  public record SearchHelp(
      String visibility,
      @Schema(nullable = true) String recommendedQuery,
      @Schema(nullable = true) Instant checkedAt) {}

  @Schema(requiredProperties = {"platform", "url"})
  public record ExternalLink(String platform, String url) {}

  @Schema(requiredProperties = {"provider", "status", "number"})
  public record Karaoke(String provider, String status, @Schema(nullable = true) String number) {}

  @Schema(requiredProperties = {"workId", "links", "karaoke"})
  public record WorkResources(UUID workId, List<ExternalLink> links, List<Karaoke> karaoke) {}

  @Schema(
      requiredProperties = {
        "song",
        "representativeVideo",
        "searchHelp",
        "relatedSongs",
        "links",
        "karaoke",
        "workResources"
      })
  public record Detail(
      Card song,
      Video representativeVideo,
      SearchHelp searchHelp,
      List<Card> relatedSongs,
      List<ExternalLink> links,
      List<Karaoke> karaoke,
      @Schema(nullable = true) WorkResources workResources) {}

  @Schema(requiredProperties = {"items", "nextCursor", "hasNext"})
  public record Page(
      List<Card> items, @Schema(nullable = true) String nextCursor, boolean hasNext) {}

  @Schema(requiredProperties = {"items"})
  public record Recommendations(List<Card> items) {}
}
