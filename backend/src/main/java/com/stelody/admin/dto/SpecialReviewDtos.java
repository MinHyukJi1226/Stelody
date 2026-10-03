package com.stelody.admin.dto;

import com.stelody.admin.domain.AnniversaryRules.Evidence;
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

  public record Item(
      UUID id,
      UUID songId,
      String title,
      long songVersion,
      boolean isSpecialEvent,
      String specialEventLabel,
      Status status,
      long version,
      List<Evidence> evidence,
      boolean basisCurrent,
      Instant createdAt) {}

  public record Page(List<Item> items, int page, int size, boolean hasMore) {}

  public record Change(
      @NotNull @PositiveOrZero Long version,
      @NotNull @PositiveOrZero Long songVersion,
      @NotNull Status status,
      @Size(max = 60) String label,
      @NotBlank @Size(max = 500) String reason) {}

  public record Refresh(UUID songId, UUID candidateId) {}
}
