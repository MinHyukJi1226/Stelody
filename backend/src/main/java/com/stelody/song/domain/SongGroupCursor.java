package com.stelody.song.domain;

import com.stelody.catalog.web.CatalogException;
import com.stelody.song.repository.SongGroupRepository.Position;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class SongGroupCursor {
  public record Token(
      int version,
      String purpose,
      String groupBy,
      String sort,
      String groupId,
      UUID publication,
      Position position,
      String songCursor) {}

  private final ObjectMapper mapper;

  public SongGroupCursor(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public Token decode(
      String value, String purpose, String groupBy, String sort, String groupId, UUID publication) {
    if (value == null) return null;
    try {
      if (value.isEmpty() || value.length() > 4096) throw CatalogException.invalid();
      var token = mapper.readValue(Base64.getUrlDecoder().decode(value), Token.class);
      if (token.version() != 1
          || !purpose.equals(token.purpose())
          || !groupBy.equals(token.groupBy())
          || !sort.equals(token.sort())
          || !Objects.equals(groupId, token.groupId())) throw CatalogException.invalid();
      if (purpose.equals("GROUPS")) {
        var p = token.position();
        if (p == null
            || token.songCursor() != null
            || p.section() < 0
            || p.section() > 1
            || p.activity() < 0
            || p.activity() > 2
            || p.generation() < 0
            || p.generation() > 32768
            || p.display() < 0
            || p.debut() == null
            || p.debut().getYear() < 1
            || p.debut().getYear() > 9999
            || p.name() == null
            || p.name().length() > 400
            || p.id() == null
            || p.id().isEmpty()
            || p.id().length() > 36) throw CatalogException.invalid();
      } else if (token.position() != null
          || token.songCursor() == null
          || token.songCursor().isEmpty()) throw CatalogException.invalid();
      if (sort.equals("VIEWS") && !Objects.equals(publication, token.publication()))
        throw new CatalogException(409, "VIEW_PUBLICATION_CHANGED", "조회수가 갱신되었습니다. 목록을 새로고침해 주세요");
      return token;
    } catch (CatalogException e) {
      throw e;
    } catch (RuntimeException e) {
      throw CatalogException.invalid();
    }
  }

  public String groups(String groupBy, String sort, UUID publication, Position position) {
    return encode(new Token(1, "GROUPS", groupBy, sort, null, publication, position, null));
  }

  public String songs(String groupBy, String sort, String groupId, UUID publication, String inner) {
    return encode(new Token(1, "SONGS", groupBy, sort, groupId, publication, null, inner));
  }

  private String encode(Token token) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(token));
  }
}
