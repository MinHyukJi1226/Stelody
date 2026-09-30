package com.stelody.favorite.service;

import com.stelody.favorite.domain.Favorite;
import com.stelody.favorite.dto.FavoriteDtos;
import com.stelody.favorite.repository.FavoriteQueries;
import com.stelody.favorite.repository.FavoriteRepository;
import com.stelody.favorite.web.FavoriteException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FavoriteService {
  private final FavoriteRepository favorites;
  private final FavoriteQueries queries;
  private final int limit;

  public FavoriteService(
      FavoriteRepository favorites,
      FavoriteQueries queries,
      @Value("${stelody.favorites.limit:5000}") int limit) {
    if (limit < 1) throw new IllegalArgumentException("Favorite limit must be positive");
    this.favorites = favorites;
    this.queries = queries;
    this.limit = limit;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void save(UUID userId, UUID songId) {
    queries.lockActiveAccount(userId);
    if (favorites.existsById(new Favorite.Key(userId, songId))) return;
    if (!queries.publiclyAvailable(songId))
      throw new FavoriteException(404, "CATALOG_NOT_FOUND", "공개된 곡을 찾을 수 없습니다");
    if (favorites.countByIdUserId(userId) >= limit)
      throw new FavoriteException(409, "FAVORITE_LIMIT_REACHED", "즐겨찾기 저장 한도에 도달했습니다");
    favorites.save(new Favorite(userId, songId, Instant.now().truncatedTo(ChronoUnit.MICROS)));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void delete(UUID userId, UUID songId) {
    queries.lockActiveAccount(userId);
    favorites.deleteOwned(userId, songId);
  }

  @Transactional(readOnly = true)
  public FavoriteDtos.State state(UUID userId, UUID songId) {
    return new FavoriteDtos.State(songId, favorites.existsById(new Favorite.Key(userId, songId)));
  }
}
