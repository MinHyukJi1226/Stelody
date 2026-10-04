package com.stelody.export.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class ExportDtos {
  private ExportDtos() {}

  @Schema(requiredProperties = {"authorizationUrl", "expiresAt"})
  public record Authorization(String authorizationUrl, Instant expiresAt) {}

  @Schema(requiredProperties = {"status"})
  public record Connection(String status) {}

  public record Create(@NotNull UUID requestId, @NotNull @Min(0) Long version) {}

  @Schema(
      requiredProperties = {
        "id",
        "playlistId",
        "version",
        "status",
        "totalCount",
        "copiedCount",
        "skippedCount",
        "remainingCount",
        "youtubePlaylistId",
        "youtubeUrl",
        "errorCode",
        "createdAt",
        "updatedAt"
      })
  public record Job(
      UUID id,
      UUID playlistId,
      long version,
      String status,
      int totalCount,
      int copiedCount,
      int skippedCount,
      int remainingCount,
      @Schema(nullable = true) String youtubePlaylistId,
      @Schema(nullable = true) String youtubeUrl,
      @Schema(nullable = true) String errorCode,
      Instant createdAt,
      Instant updatedAt) {}
}
