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

  @Schema(requiredProperties = {"items", "nextCursor", "hasNext", "totalCount"})
  public record Page(
      List<Card> items,
      @Schema(nullable = true) String nextCursor,
      boolean hasNext,
      @Schema(
              description =
                  "현재 요청의 검색어·필터·공개 조건에 일치하는 전체 고유 곡 수입니다. 페이지 크기·정렬·커서와 무관하며 결과가 없거나 빈 검색이면 0입니다. 각 요청의 items와 같은 DB 스냅샷에서 집계하며 요청 사이 카탈로그 변경 시 달라질 수 있습니다.",
              minimum = "0")
          long totalCount) {}

  @Schema(requiredProperties = {"items"})
  public record Recommendations(List<Card> items) {}
}
