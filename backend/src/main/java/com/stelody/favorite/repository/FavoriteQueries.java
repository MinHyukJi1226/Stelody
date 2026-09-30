package com.stelody.favorite.repository;

import com.stelody.catalog.repository.PublicCatalogSql;
import com.stelody.favorite.web.FavoriteException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FavoriteQueries {
  private final JdbcClient jdbc;

  public FavoriteQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // Both saves and deletes lock the same account row, including when the list is empty.
  // The lock is held through JPA's commit so concurrent saves cannot exceed the per-user limit.
  public void lockActiveAccount(UUID userId) {
    var active =
        jdbc.sql("SELECT status FROM app.app_user WHERE id = :userId FOR UPDATE")
            .param("userId", userId)
            .query(String.class)
            .optional();
    if (active.isEmpty() || !active.get().equals("ACTIVE"))
      throw new FavoriteException(401, "SESSION_EXPIRED", "다시 로그인해 주세요");
  }

  public boolean publiclyAvailable(UUID songId) {
    return jdbc.sql(
            PublicCatalogSql.SONGS
                + " SELECT EXISTS (SELECT 1 FROM public_songs WHERE id = :songId)")
        .param("songId", songId)
        .query(Boolean.class)
        .single();
  }
}
