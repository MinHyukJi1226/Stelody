package com.stelody.export.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class ExportDtos {
  private ExportDtos() {}

  public record Authorization(String authorizationUrl, Instant expiresAt) {}

  public record Connection(String status) {}

  public record Create(@NotNull UUID requestId, @NotNull @Min(0) Long version) {}

  public record Job(
      UUID id,
      UUID playlistId,
      long version,
      String status,
      int totalCount,
      int copiedCount,
      int skippedCount,
      int remainingCount,
      String youtubePlaylistId,
      String youtubeUrl,
      String errorCode,
      Instant createdAt,
      Instant updatedAt) {}
}
