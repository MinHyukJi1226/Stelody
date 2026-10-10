package com.stelody.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class CatalogAdminDtos {
  private CatalogAdminDtos() {}

  public enum Activity {
    ACTIVE,
    GRADUATED
  }

  public enum ChannelType {
    GROUP,
    MEMBER,
    EXTERNAL
  }

  public enum SongType {
    ORIGINAL,
    COVER
  }

  public enum Visibility {
    DRAFT,
    PUBLISHED,
    HIDDEN
  }

  public enum SearchVisibility {
    UNCHECKED,
    NORMAL,
    DIFFICULT
  }

  public enum VideoKind {
    OFFICIAL_MV,
    OFFICIAL_COVER,
    AUDIO,
    REUPLOAD,
    OTHER
  }

  public enum Provider {
    TJ,
    KY
  }

  public enum KaraokeStatus {
    UNKNOWN,
    NOT_LISTED,
    REGISTERED
  }

  public record Link(
      @NotBlank @Size(max = 60) String platform,
      @NotBlank @Size(max = 2000) String url,
      @NotBlank @Size(max = 2000) String sourceUrl) {}

  public record Karaoke(
      @NotNull Provider provider,
      @NotNull KaraokeStatus status,
      @Size(max = 40) String number,
      @Size(max = 2000) String sourceUrl) {}

  public record MemberInput(
      @NotNull @Min(0) Long version,
      @NotBlank @Size(max = 100) String name,
      @Min(1) @Max(32767) Integer generation,
      @Schema(description = "기수와 별도로 관리하는 유닛명. 생략·null·빈 문자열이면 미등록 또는 기존 값 삭제.", nullable = true)
          @Size(max = 100)
          String unitName,
      @Schema(description = "치지직 HTTPS 링크. 생략·null·빈 문자열이면 미등록 또는 기존 값 삭제.", nullable = true)
          @Size(max = 2000)
          String chzzkUrl,
      @Schema(description = "X HTTPS 링크. 생략·null·빈 문자열이면 미등록 또는 기존 값 삭제.", nullable = true)
          @Size(max = 2000)
          String xUrl,
      @NotNull Activity activityStatus,
      @Size(max = 2000) String profileImageUrl,
      LocalDate debutDate,
      @Min(1) @Max(12) Integer birthdayMonth,
      @Min(1) @Max(31) Integer birthdayDay,
      @NotNull @Size(max = 50) List<@NotBlank @Size(max = 200) String> aliases,
      @NotBlank @Size(max = 500) String reason) {}

  public record ArtistInput(
      @NotNull @Min(0) Long version,
      @NotBlank @Size(max = 200) String name,
      @NotNull @Size(max = 50) List<@NotBlank @Size(max = 200) String> aliases,
      @NotBlank @Size(max = 500) String reason) {}

  public record WorkInput(
      @NotNull @Min(0) Long version,
      @NotBlank @Size(max = 300) String title,
      @NotNull @Size(max = 50) List<@NotBlank @Size(max = 300) String> aliases,
      @NotNull @Size(max = 100) List<@NotNull UUID> artistIds,
      @NotNull @Size(max = 20) List<@NotNull @Valid Link> links,
      @NotNull @Size(max = 2) List<@NotNull @Valid Karaoke> karaoke,
      @NotBlank @Size(max = 500) String reason) {}

  public record ChannelInput(
      @NotNull @Min(0) Long version,
      @NotBlank @Pattern(regexp = "UC[A-Za-z0-9_-]{22}") String youtubeId,
      @NotBlank @Size(max = 200) String name,
      UUID memberId,
      @NotNull ChannelType channelType,
      @NotNull Boolean collectionEnabled,
      @NotBlank @Size(max = 500) String reason) {}

  public record SongInput(
      @NotNull @Min(0) Long version,
      @NotBlank @Size(max = 300) String title,
      @NotNull SongType type,
      UUID workId,
      @NotNull Visibility visibility,
      UUID representativeVideoId,
      @NotNull @Size(max = 50) List<@NotBlank @Size(max = 300) String> aliases,
      @NotNull @Size(max = 100) List<@NotNull UUID> memberIds,
      @NotNull @Size(max = 100) List<@NotNull UUID> externalArtistIds,
      @NotNull Boolean isSpecialEvent,
      @Size(max = 60) String specialEventLabel,
      @NotNull SearchVisibility searchVisibility,
      @Size(max = 300) String recommendedSearchQuery,
      @NotNull @Size(max = 20) List<@NotNull @Valid Link> links,
      @NotNull @Size(max = 2) List<@NotNull @Valid Karaoke> karaoke,
      @NotBlank @Size(max = 500) String reason) {}

  public record Registration(
      @NotNull @Min(0) Long version,
      @NotNull UUID songId,
      @NotNull @Min(0) Long songVersion,
      @NotNull VideoKind kind,
      @NotBlank @Size(max = 500) String reason) {}

  public record VideoInput(
      @NotNull @Min(0) Long version,
      @NotNull VideoKind kind,
      Instant publishedAt,
      @Size(max = 2000) String thumbnailUrl,
      @NotBlank @Size(max = 500) String reason) {}

  public record VideoRegistration(
      @NotNull @Min(0) Long version,
      @NotNull @Min(0) Long reviewVersion,
      @NotBlank @Size(max = 2000) String videoUrl,
      @NotNull VideoKind kind,
      @NotBlank @Size(max = 500) String reason) {}

  @Schema(requiredProperties = {"id", "name", "version", "status"})
  public record Summary(UUID id, String name, long version, String status) {}

  @Schema(requiredProperties = {"items", "page", "size", "hasNext"})
  public record Page(List<Summary> items, int page, int size, boolean hasNext) {}

  @Schema(requiredProperties = {"item", "possibleDuplicateIds"})
  public record Saved<T>(T item, List<UUID> possibleDuplicateIds) {}

  @Schema(
      requiredProperties = {
        "id",
        "version",
        "name",
        "generation",
        "unitName",
        "chzzkUrl",
        "xUrl",
        "activityStatus",
        "profileImageUrl",
        "debutDate",
        "birthdayMonth",
        "birthdayDay",
        "aliases"
      })
  public record Member(
      UUID id,
      long version,
      String name,
      @Schema(nullable = true) Integer generation,
      @Schema(description = "기수와 별도로 관리하는 유닛명. 미등록이면 null.", nullable = true) String unitName,
      @Schema(description = "치지직 HTTPS 링크. 미등록이면 null.", nullable = true) String chzzkUrl,
      @Schema(description = "X HTTPS 링크. 미등록이면 null.", nullable = true) String xUrl,
      String activityStatus,
      @Schema(nullable = true) String profileImageUrl,
      @Schema(nullable = true) LocalDate debutDate,
      @Schema(nullable = true) Integer birthdayMonth,
      @Schema(nullable = true) Integer birthdayDay,
      List<String> aliases) {}

  @Schema(requiredProperties = {"id", "version", "name", "aliases"})
  public record Artist(UUID id, long version, String name, List<String> aliases) {}

  @Schema(
      requiredProperties = {"id", "version", "title", "aliases", "artistIds", "links", "karaoke"})
  public record Work(
      UUID id,
      long version,
      String title,
      List<String> aliases,
      List<UUID> artistIds,
      List<LinkView> links,
      List<KaraokeView> karaoke) {}

  @Schema(
      requiredProperties = {
        "id",
        "version",
        "youtubeId",
        "name",
        "memberId",
        "channelType",
        "collectionEnabled"
      })
  public record Channel(
      UUID id,
      long version,
      String youtubeId,
      String name,
      @Schema(nullable = true) UUID memberId,
      String channelType,
      boolean collectionEnabled) {}

  @Schema(
      requiredProperties = {
        "id",
        "version",
        "songId",
        "youtubeId",
        "kind",
        "availability",
        "sourceTitle",
        "sourcePublishedAt",
        "sourceThumbnailUrl",
        "sourceObservedAt",
        "publishedAt",
        "thumbnailUrl",
        "embeddable"
      })
  public record Video(
      UUID id,
      long version,
      UUID songId,
      String youtubeId,
      String kind,
      String availability,
      @Schema(nullable = true) String sourceTitle,
      @Schema(nullable = true) Instant sourcePublishedAt,
      @Schema(nullable = true) String sourceThumbnailUrl,
      @Schema(nullable = true) Instant sourceObservedAt,
      @Schema(nullable = true) Instant publishedAt,
      @Schema(nullable = true) String thumbnailUrl,
      boolean embeddable) {}

  @Schema(
      requiredProperties = {
        "id",
        "version",
        "title",
        "type",
        "workId",
        "visibility",
        "representativeVideoId",
        "aliases",
        "memberIds",
        "externalArtistIds",
        "isSpecialEvent",
        "specialEventLabel",
        "searchVisibility",
        "recommendedSearchQuery",
        "searchCheckedAt",
        "videos",
        "links",
        "karaoke",
        "missingFields"
      })
  public record Song(
      UUID id,
      long version,
      String title,
      String type,
      @Schema(nullable = true) UUID workId,
      String visibility,
      @Schema(nullable = true) UUID representativeVideoId,
      List<String> aliases,
      List<UUID> memberIds,
      List<UUID> externalArtistIds,
      boolean isSpecialEvent,
      @Schema(nullable = true) String specialEventLabel,
      String searchVisibility,
      @Schema(nullable = true) String recommendedSearchQuery,
      @Schema(nullable = true) Instant searchCheckedAt,
      List<Video> videos,
      List<LinkView> links,
      List<KaraokeView> karaoke,
      List<String> missingFields) {}

  @Schema(requiredProperties = {"id", "platform", "url", "sourceUrl", "checkedAt"})
  public record LinkView(
      UUID id, String platform, String url, String sourceUrl, Instant checkedAt) {}

  @Schema(requiredProperties = {"id", "provider", "status", "number", "sourceUrl", "checkedAt"})
  public record KaraokeView(
      UUID id,
      String provider,
      String status,
      @Schema(nullable = true) String number,
      @Schema(nullable = true) String sourceUrl,
      @Schema(nullable = true) Instant checkedAt) {}

  @Schema(requiredProperties = {"items", "page", "size", "hasNext"})
  public record AuditPage(List<Audit> items, int page, int size, boolean hasNext) {}

  @Schema(
      requiredProperties = {
        "id",
        "targetType",
        "targetId",
        "actorId",
        "action",
        "before",
        "after",
        "reason",
        "changedAt"
      })
  public record Audit(
      UUID id,
      String targetType,
      UUID targetId,
      @Schema(nullable = true) UUID actorId,
      String action,
      @Schema(
              types = {"object", "array", "string", "number", "boolean", "null"},
              description = "대상 유형별 변경 전후 JSON 자료")
          Object before,
      @Schema(
              types = {"object", "array", "string", "number", "boolean", "null"},
              description = "대상 유형별 변경 전후 JSON 자료")
          Object after,
      String reason,
      Instant changedAt) {}
}
