package com.stelody.playlist.domain;

import com.stelody.playlist.web.PlaylistException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class PlaylistCursor {
  public record ListPosition(int format, UUID userId, Instant createdAt, UUID id) {}

  public record ItemPosition(
      int format, UUID userId, UUID playlistId, Long version, Integer position) {}

  private final ObjectMapper mapper;

  public PlaylistCursor(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public ListPosition lists(String cursor, UUID userId) {
    if (cursor == null) return null;
    var p = decode(cursor, ListPosition.class);
    if (p.format() != 1 || !userId.equals(p.userId()) || p.id() == null || p.createdAt() == null)
      throw PlaylistException.invalid();
    if (p.createdAt().getNano() % 1000 != 0
        || p.createdAt().isBefore(Instant.parse("0001-01-01T00:00:00Z"))
        || !p.createdAt().isBefore(Instant.parse("+10000-01-01T00:00:00Z")))
      throw PlaylistException.invalid();
    return p;
  }

  public ItemPosition items(String cursor, UUID userId, UUID playlistId, long version) {
    if (cursor == null) return null;
    var p = decode(cursor, ItemPosition.class);
    if (p.format() != 1
        || !userId.equals(p.userId())
        || !playlistId.equals(p.playlistId())
        || p.version() == null
        || p.position() == null
        || p.version() < 0
        || p.position() < 0) throw PlaylistException.invalid();
    if (p.version() != version) throw PlaylistException.changed();
    return p;
  }

  public String lists(UUID userId, Instant createdAt, UUID id) {
    return encode(new ListPosition(1, userId, createdAt, id));
  }

  public String items(UUID userId, UUID playlistId, long version, int position) {
    return encode(new ItemPosition(1, userId, playlistId, version, position));
  }

  private <T> T decode(String cursor, Class<T> type) {
    try {
      if (cursor.isEmpty() || cursor.length() > 1024) throw PlaylistException.invalid();
      var p = mapper.readValue(Base64.getUrlDecoder().decode(cursor), type);
      if (p == null) throw PlaylistException.invalid();
      return p;
    } catch (RuntimeException exception) {
      throw PlaylistException.invalid();
    }
  }

  private String encode(Object value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(value));
  }
}
