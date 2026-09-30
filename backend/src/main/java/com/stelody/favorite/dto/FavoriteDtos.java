package com.stelody.favorite.dto;

import java.util.UUID;

public final class FavoriteDtos {
  private FavoriteDtos() {}

  public record State(UUID songId, boolean favorited) {}
}
