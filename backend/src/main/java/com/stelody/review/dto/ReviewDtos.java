package com.stelody.review.dto;

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

  public record Item(
      UUID id,
      String youtubeId,
      UUID channelId,
      String channelName,
      String title,
      Instant publishedAt,
      String thumbnailUrl,
      Long durationSeconds,
      Instant observedAt,
      String availability,
      String disposition,
      String suggestedType,
      String ruleVersion,
      String decisionReason,
      String status,
      String reviewNote,
      Instant firstSeenAt,
      long version,
      boolean sourceExpired) {}

  public record Page(List<Item> items, int page, int size, boolean hasNext) {}
}
