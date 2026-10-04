package com.stelody.admin.dto;

import com.stelody.admin.domain.AnniversaryRules.Evidence;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class SpecialReviewDtos {
  private SpecialReviewDtos() {}

  public enum Status {
    PENDING,
    CONFIRMED,
    DISMISSED
  }

  @Schema(
      requiredProperties = {
        "id",
        "songId",
        "title",
        "songVersion",
        "isSpecialEvent",
        "specialEventLabel",
        "status",
        "version",
        "evidence",
        "basisCurrent",
        "createdAt"
      })
  public record Item(
      UUID id,
      UUID songId,
      String title,
      long songVersion,
      boolean isSpecialEvent,
      @Schema(nullable = true) String specialEventLabel,
      Status status,
      long version,
      List<Evidence> evidence,
      boolean basisCurrent,
      Instant createdAt) {}

  @Schema(requiredProperties = {"items", "page", "size", "hasMore"})
  public record Page(List<Item> items, int page, int size, boolean hasMore) {}

  public record Change(
      @NotNull @PositiveOrZero Long version,
      @NotNull @PositiveOrZero Long songVersion,
      @NotNull Status status,
      @Size(max = 60) String label,
      @NotBlank @Size(max = 500) String reason) {}

  @Schema(requiredProperties = {"songId", "candidateId"})
  public record Refresh(UUID songId, @Schema(nullable = true) UUID candidateId) {}
}
