package com.stelody.favorite.repository;

import com.stelody.favorite.domain.Favorite;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FavoriteRepository extends JpaRepository<Favorite, Favorite.Key> {
  long countByIdUserId(UUID userId);

  @Modifying
  @Query("DELETE FROM Favorite f WHERE f.id.userId = :userId AND f.id.songId = :songId")
  int deleteOwned(@Param("userId") UUID userId, @Param("songId") UUID songId);
}
