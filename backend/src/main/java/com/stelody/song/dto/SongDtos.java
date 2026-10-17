package com.stelody.song.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SongDtos {
  private SongDtos() {}

  @Schema(requiredProperties = {"id", "name", "kind", "activityStatus"})
  public record Participant(
      UUID id,
      String name,
      @Schema(
              allowableValues = {"MEMBER", "EXTERNAL"},
              description = "MEMBER: 스텔라이브 멤버, EXTERNAL: 외부 아티스트")
          String kind,
      @Schema(
              nullable = true,
              allowableValues = {"ACTIVE", "GRADUATED"},
              description = "ACTIVE: 활동 중, GRADUATED: 졸업. 외부 아티스트는 null입니다.")
          String activityStatus) {}

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
      @Schema(
              allowableValues = {"ORIGINAL", "COVER"},
              description = "ORIGINAL: 오리지널곡, COVER: 커버곡. 콜라보 여부는 collaboration으로 별도 표시합니다.")
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
  public record Video(
      UUID id,
      String youtubeId,
      @Schema(
              allowableValues = {"OFFICIAL_MV", "OFFICIAL_COVER", "AUDIO", "REUPLOAD", "OTHER"},
              description =
                  "OFFICIAL_MV: 공식 MV, OFFICIAL_COVER: 공식 커버, AUDIO: 음원, REUPLOAD: 재업로드, OTHER: 기타")
          String kind,
      String url,
      boolean embeddable) {}

  @Schema(requiredProperties = {"visibility", "recommendedQuery", "checkedAt"})
  public record SearchHelp(
      @Schema(
              allowableValues = {"UNCHECKED", "NORMAL", "DIFFICULT"},
              description =
                  "YouTube 검색 확인 상태. UNCHECKED: 미확인, NORMAL: 정상 검색, DIFFICULT: 검색 어려움. 사이트 공개 설정과 별개입니다.")
          String visibility,
      @Schema(nullable = true) String recommendedQuery,
      @Schema(nullable = true) Instant checkedAt) {}

  @Schema(requiredProperties = {"platform", "url"})
  public record ExternalLink(String platform, String url) {}

  @Schema(requiredProperties = {"provider", "status", "number"})
  public record Karaoke(
      @Schema(allowableValues = {"TJ", "KY"}) String provider,
      @Schema(
              allowableValues = {"UNKNOWN", "NOT_LISTED", "REGISTERED"},
              description = "UNKNOWN: 미확인, NOT_LISTED: 미등록 확인, REGISTERED: 등록 확인")
          String status,
      @Schema(nullable = true) String number) {}

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
      @ArraySchema(
              uniqueItems = true,
              maxItems = 6,
              arraySchema =
                  @Schema(
                      description =
                          "같은 work.id의 다른 COVER 중 현재 곡에 없는 확정 멤버가 한 명 이상 참여한 곡입니다. "
                              + "현재 곡과 같은 멤버만 참여한 곡은 제외합니다. 일반 공개 목록과 동일하게 공개 설정·대표 영상의 공개 상태·공개일·확정 멤버 조건을 적용합니다. "
                              + "곡 ID 중복 없이 조건 적용 후 대표 영상 publishedAt 내림차순, 동률이면 곡 ID 내림차순으로 최대 6개를 반환합니다. "
                              + "원곡 정보나 일치하는 커버가 없으면 빈 배열이며 페이지 조회는 지원하지 않습니다."))
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

  @Schema(requiredProperties = {"years"})
  public record Years(
      @ArraySchema(
              uniqueItems = true,
              arraySchema =
                  @Schema(
                      description =
                          "공개 곡이 있는 검색 가능 연도(1900~2100)를 중복 없이 내림차순으로 반환합니다. 한국 시간 기준이며 해당 곡이 없으면 빈 배열입니다. 연도 선택의 전체 옵션이고 현재 검색어·필터와 무관합니다.",
                      example = "[2026, 2025, 2023]"),
              schema =
                  @Schema(type = "integer", format = "int32", minimum = "1900", maximum = "2100"))
          List<Integer> years) {}
}
