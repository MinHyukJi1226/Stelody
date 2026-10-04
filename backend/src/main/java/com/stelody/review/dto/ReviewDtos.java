package com.stelody.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReviewDtos {
  private ReviewDtos() {}

  public record Change(
      @NotNull @Min(0) Long version,
      @Pattern(regexp = "PENDING|IGNORED") @NotBlank String status,
      @NotBlank @Size(max = 500) String reason) {}

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
