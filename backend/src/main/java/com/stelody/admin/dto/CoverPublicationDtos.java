package com.stelody.admin.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class CoverPublicationDtos {
  private CoverPublicationDtos() {}

  public record Control(boolean enabled, long version, boolean policyAllowed) {}

  public record Change(
      @NotNull @PositiveOrZero Long version,
      @NotNull Boolean enabled,
      @NotBlank @Size(max = 500) String reason) {}

  public record Registration(
      UUID reviewId,
      UUID songId,
      UUID videoId,
      String title,
      String visibility,
      String ruleVersion,
      String reason,
      Instant processedAt,
      List<String> missingFields) {}

  public record Page(List<Registration> items, int page, int size, boolean hasNext) {}
}
