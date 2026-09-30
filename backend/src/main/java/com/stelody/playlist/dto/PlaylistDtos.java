package com.stelody.playlist.dto;

import com.stelody.song.dto.SongDtos;
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

  public record Summary(
      UUID id,
      String name,
      long version,
      Instant createdAt,
      Instant updatedAt,
      long totalCount,
      long availableCount) {}

  public record Page(List<Summary> items, String nextCursor, boolean hasNext, long totalCount) {}

  public record Item(
      UUID id,
      UUID songId,
      int position,
      Instant addedAt,
      boolean available,
      String unavailableMessage,
      SongDtos.Card song) {}

  public record Items(
      long version,
      List<Item> items,
      String nextCursor,
      boolean hasNext,
      long totalCount,
      long availableCount) {}

  public record Added(Summary playlist, UUID itemId) {}
}
