package com.stelody.favorite.dto;

import com.stelody.song.dto.SongDtos;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FavoriteDtos {
  private FavoriteDtos() {}

  @Schema(requiredProperties = {"songId", "favorited"})
  public record State(UUID songId, boolean favorited) {}

  @Schema(requiredProperties = {"songId", "savedAt", "available", "unavailableMessage", "song"})
  public record Item(
      UUID songId,
      Instant savedAt,
      boolean available,
      @Schema(nullable = true) String unavailableMessage,
      @Schema(nullable = true) SongDtos.Card song) {}

  @Schema(requiredProperties = {"items", "nextCursor", "hasNext", "totalCount", "availableCount"})
  public record Page(
      List<Item> items,
      @Schema(nullable = true) String nextCursor,
      boolean hasNext,
      long totalCount,
      long availableCount) {}
}
