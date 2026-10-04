package com.stelody.collection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CollectionDtos {
  private CollectionDtos() {}

  @Schema(
      requiredProperties = {
        "id",
        "kind",
        "mode",
        "channelId",
        "logicalSlot",
        "status",
        "attempt",
        "startedAt",
        "finishedAt",
        "errorCode",
        "totalCount",
        "observedCount",
        "skippedCount",
        "pendingCount",
        "pages",
        "candidates",
        "ruleVersion"
      })
  public record Run(
      UUID id,
      String kind,
      @Schema(nullable = true) String mode,
      @Schema(nullable = true) UUID channelId,
      @Schema(nullable = true) Instant logicalSlot,
      String status,
      int attempt,
      Instant startedAt,
      @Schema(nullable = true) Instant finishedAt,
      @Schema(nullable = true) String errorCode,
      long totalCount,
      long observedCount,
      long skippedCount,
      long pendingCount,
      int pages,
      int candidates,
      @Schema(nullable = true) String ruleVersion) {}

  @Schema(requiredProperties = {"items", "page", "size", "hasNext"})
  public record Page(List<Run> items, int page, int size, boolean hasNext) {}

  @Schema(
      requiredProperties = {
        "latest",
        "lastSuccessAt",
        "lastFailureAt",
        "missedSlots",
        "delayed",
        "runningOverdue"
      })
  public record Health(
      @Schema(nullable = true) Run latest,
      @Schema(nullable = true) Instant lastSuccessAt,
      @Schema(nullable = true) Instant lastFailureAt,
      long missedSlots,
      boolean delayed,
      boolean runningOverdue) {}

  @Schema(
      requiredProperties = {
        "checkedAt",
        "retryEnabled",
        "video",
        "discovery",
        "pendingReviews",
        "deferredReviews",
        "activeRetry"
      })
  public record Overview(
      Instant checkedAt,
      boolean retryEnabled,
      Health video,
      Health discovery,
      long pendingReviews,
      long deferredReviews,
      @Schema(nullable = true) Retry activeRetry) {}

  @Schema(
      requiredProperties = {
        "id",
        "kind",
        "runId",
        "expectedAttempt",
        "status",
        "createdAt",
        "startedAt",
        "finishedAt",
        "executionRunId",
        "errorCode"
      })
  public record Retry(
      UUID id,
      String kind,
      UUID runId,
      int expectedAttempt,
      String status,
      Instant createdAt,
      @Schema(nullable = true) Instant startedAt,
      @Schema(nullable = true) Instant finishedAt,
      @Schema(nullable = true) UUID executionRunId,
      @Schema(nullable = true) String errorCode) {}

  public record RetryInput(
      @NotNull UUID requestId,
      @NotNull @Min(1) Integer attempt,
      @NotBlank @Size(max = 500) String reason) {}
}
