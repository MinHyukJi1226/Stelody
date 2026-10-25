package com.stelody.review.dto;

import com.stelody.admin.dto.CatalogAdminDtos.SongInput;
import com.stelody.admin.dto.CatalogAdminDtos.VideoKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ReviewDtos {
  private ReviewDtos() {}

  public record Change(
      @NotNull @Min(0) Long version,
      @Pattern(regexp = "PENDING|IGNORED") @NotBlank String status,
      @NotBlank @Size(max = 500) String reason) {}

  @Schema(requiredProperties = {"id", "version"})
  public record Candidate(@NotNull UUID id, @NotNull @Min(0) Long version) {}

  @Schema(requiredProperties = {"items", "hasMore", "channelMembers"})
  public record CompletionPreview(
      List<Item> items, boolean hasMore, Map<UUID, UUID> channelMembers) {}

  public record Selection(
      @NotNull UUID id, @NotNull @Valid SongInput song, @NotNull VideoKind kind) {}

  public record Complete(
      @NotNull UUID requestId,
      @NotNull @Size(min = 1, max = 10000) List<@NotNull @Valid Candidate> items,
      @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Selection> selected) {}

  @Schema(requiredProperties = {"reviewId", "songId"})
  public record RegisteredSong(UUID reviewId, UUID songId) {}

  @Schema(requiredProperties = {"registeredSongs", "deletedCount", "replayed"})
  public record CompletionResult(
      List<RegisteredSong> registeredSongs, int deletedCount, boolean replayed) {}

  @Schema(
      requiredProperties = {
        "id",
        "youtubeId",
        "channelId",
        "channelName",
        "title",
        "publishedAt",
        "thumbnailUrl",
        "durationSeconds",
        "observedAt",
        "availability",
        "disposition",
        "suggestedType",
        "ruleVersion",
        "decisionReason",
        "status",
        "reviewNote",
        "firstSeenAt",
        "version",
        "sourceExpired",
        "registeredVideoId"
      })
  public record Item(
      UUID id,
      String youtubeId,
      @Schema(nullable = true) UUID channelId,
      @Schema(nullable = true) String channelName,
      @Schema(nullable = true) String title,
      @Schema(nullable = true) Instant publishedAt,
      @Schema(nullable = true) String thumbnailUrl,
      @Schema(nullable = true) Long durationSeconds,
      @Schema(nullable = true) Instant observedAt,
      String availability,
      String disposition,
      String suggestedType,
      String ruleVersion,
      String decisionReason,
      String status,
      @Schema(nullable = true) String reviewNote,
      Instant firstSeenAt,
      long version,
      boolean sourceExpired,
      @Schema(nullable = true) UUID registeredVideoId) {}

  @Schema(requiredProperties = {"items", "page", "size", "hasNext"})
  public record Page(List<Item> items, int page, int size, boolean hasNext) {}
}
