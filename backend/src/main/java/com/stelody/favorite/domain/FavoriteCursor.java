package com.stelody.favorite.domain;

import com.stelody.favorite.web.FavoriteException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class FavoriteCursor {
  public record Position(int version, UUID userId, Instant savedAt, UUID songId) {}

  private final ObjectMapper mapper;

  public FavoriteCursor(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public Position decode(String cursor, UUID userId) {
    if (cursor == null) return null;
    try {
      if (cursor.isEmpty() || cursor.length() > 1024) throw FavoriteException.invalid();
      var position = mapper.readValue(Base64.getUrlDecoder().decode(cursor), Position.class);
      if (position.version() != 1
          || !userId.equals(position.userId())
          || position.savedAt() == null
          || position.songId() == null) throw FavoriteException.invalid();
      // PostgreSQL timestamps have microsecond precision; reject timestamps the driver would round.
      if (position.savedAt().getNano() % 1000 != 0
          || position.savedAt().isBefore(Instant.parse("0001-01-01T00:00:00Z"))
          || !position.savedAt().isBefore(Instant.parse("+10000-01-01T00:00:00Z")))
        throw FavoriteException.invalid();
      return position;
    } catch (RuntimeException exception) {
      throw FavoriteException.invalid();
    }
  }

  public String encode(UUID userId, Instant savedAt, UUID songId) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(mapper.writeValueAsBytes(new Position(1, userId, savedAt, songId)));
  }
}
