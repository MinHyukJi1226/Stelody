package com.stelody.collection.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CollectionDtos {
  private CollectionDtos() {}

  public record Run(
      UUID id,
      String kind,
      String mode,
      UUID channelId,
      Instant logicalSlot,
      String status,
      int attempt,
      Instant startedAt,
      Instant finishedAt,
      String errorCode,
      long totalCount,
      long observedCount,
      long skippedCount,
      long pendingCount,
      int pages,
      int candidates) {}

  public record Page(List<Run> items, int page, int size, boolean hasNext) {}

  public record Health(
      Run latest,
      Instant lastSuccessAt,
      Instant lastFailureAt,
      long missedSlots,
      boolean delayed,
      boolean runningOverdue) {}

  public record Overview(
      Instant checkedAt,
      boolean retryEnabled,
      Health video,
      Health discovery,
      long pendingReviews,
      long deferredReviews,
      Retry activeRetry) {}

  public record Retry(
      UUID id,
      String kind,
      UUID runId,
      int expectedAttempt,
      String status,
      Instant createdAt,
      Instant startedAt,
      Instant finishedAt,
      UUID executionRunId,
      String errorCode) {}

  public record RetryInput(
      @NotNull UUID requestId,
      @NotNull @Min(1) Integer attempt,
      @NotBlank @Size(max = 500) String reason) {}
}
