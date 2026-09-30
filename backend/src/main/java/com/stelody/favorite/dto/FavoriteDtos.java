package com.stelody.favorite.dto;

import com.stelody.song.dto.SongDtos;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FavoriteDtos {
  private FavoriteDtos() {}

  public record State(UUID songId, boolean favorited) {}

  public record Item(
      UUID songId,
      Instant savedAt,
      boolean available,
      String unavailableMessage,
      SongDtos.Card song) {}

  public record Page(
      List<Item> items, String nextCursor, boolean hasNext, long totalCount, long availableCount) {}
}
