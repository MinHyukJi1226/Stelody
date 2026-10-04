package com.stelody.playlist.dto;

import com.stelody.song.dto.SongDtos;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PlaylistDtos {
  private PlaylistDtos() {}

  public record Create(@NotBlank String name) {}

  public record Rename(@NotBlank String name, @NotNull @Min(0) Long version) {}

  public record Add(@NotNull UUID songId, @NotNull @Min(0) Long version) {}

  public record Order(@NotNull List<@NotNull UUID> itemIds, @NotNull @Min(0) Long version) {}

  @Schema(
      requiredProperties = {
        "id",
        "name",
        "version",
        "createdAt",
        "updatedAt",
        "totalCount",
        "availableCount"
      })
  public record Summary(
      UUID id,
      String name,
      long version,
      Instant createdAt,
      Instant updatedAt,
      long totalCount,
      long availableCount) {}

  @Schema(requiredProperties = {"items", "nextCursor", "hasNext", "totalCount"})
  public record Page(
      List<Summary> items,
      @Schema(nullable = true) String nextCursor,
      boolean hasNext,
      long totalCount) {}

  @Schema(
      requiredProperties = {
        "id",
        "songId",
        "position",
        "addedAt",
        "available",
        "unavailableMessage",
        "song"
      })
  public record Item(
      UUID id,
      UUID songId,
      int position,
      Instant addedAt,
      boolean available,
      @Schema(nullable = true) String unavailableMessage,
      @Schema(nullable = true) SongDtos.Card song) {}

  @Schema(
      requiredProperties = {
        "version",
        "items",
        "nextCursor",
        "hasNext",
        "totalCount",
        "availableCount"
      })
  public record Items(
      long version,
      List<Item> items,
      @Schema(nullable = true) String nextCursor,
      boolean hasNext,
      long totalCount,
      long availableCount) {}

  @Schema(requiredProperties = {"playlist", "itemId"})
  public record Added(Summary playlist, UUID itemId) {}
}
