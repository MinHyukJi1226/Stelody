package com.stelody.admin.dto;

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

  public record Summary(UUID id, String name, long version, String status) {}

  public record Page(List<Summary> items, int page, int size, boolean hasNext) {}

  public record Saved<T>(T item, List<UUID> possibleDuplicateIds) {}

  public record Member(
      UUID id,
      long version,
      String name,
      Integer generation,
      String activityStatus,
      String profileImageUrl,
      LocalDate debutDate,
      Integer birthdayMonth,
      Integer birthdayDay,
      List<String> aliases) {}

  public record Artist(UUID id, long version, String name, List<String> aliases) {}

  public record Work(
      UUID id,
      long version,
      String title,
      List<String> aliases,
      List<UUID> artistIds,
      List<LinkView> links,
      List<KaraokeView> karaoke) {}

  public record Channel(
      UUID id,
      long version,
      String youtubeId,
      String name,
      UUID memberId,
      String channelType,
      boolean collectionEnabled) {}

  public record Video(
      UUID id,
      long version,
      UUID songId,
      String youtubeId,
      String kind,
      String availability,
      String sourceTitle,
      Instant sourcePublishedAt,
      String sourceThumbnailUrl,
      Instant sourceObservedAt,
      Instant publishedAt,
      String thumbnailUrl,
      boolean embeddable) {}

  public record Song(
      UUID id,
      long version,
      String title,
      String type,
      UUID workId,
      String visibility,
      UUID representativeVideoId,
      List<String> aliases,
      List<UUID> memberIds,
      List<UUID> externalArtistIds,
      boolean isSpecialEvent,
      String specialEventLabel,
      String searchVisibility,
      String recommendedSearchQuery,
      Instant searchCheckedAt,
      List<Video> videos,
      List<LinkView> links,
      List<KaraokeView> karaoke,
      List<String> missingFields) {}

  public record LinkView(
      UUID id, String platform, String url, String sourceUrl, Instant checkedAt) {}

  public record KaraokeView(
      UUID id,
      String provider,
      String status,
      String number,
      String sourceUrl,
      Instant checkedAt) {}

  public record AuditPage(List<Audit> items, int page, int size, boolean hasNext) {}

  public record Audit(
      UUID id,
      String targetType,
      UUID targetId,
      UUID actorId,
      String action,
      Object before,
      Object after,
      String reason,
      Instant changedAt) {}
}
