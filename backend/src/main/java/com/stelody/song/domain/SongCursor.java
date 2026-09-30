package com.stelody.song.domain;

import com.stelody.catalog.web.CatalogException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class SongCursor {
  public record Position(
      int version,
      String queryHash,
      String sort,
      UUID publication,
      int rank,
      Long views,
      Instant publishedAt,
      UUID id) {}

  private final ObjectMapper mapper;

  public SongCursor(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public Position decode(String cursor, SongQuery query, UUID publication) {
    if (cursor == null) return null;
    try {
      if (cursor.isEmpty() || cursor.length() > 2048) throw CatalogException.invalid();
      var result = mapper.readValue(Base64.getUrlDecoder().decode(cursor), Position.class);
      if (result.version() != 1
          || !hash(query).equals(result.queryHash())
          || !query.sort().equals(result.sort())
          || result.id() == null
          || result.publishedAt() == null
          || result.rank() < 1
          || result.rank() > 3
          || (result.views() != null && result.views() < 0)) throw CatalogException.invalid();
      if (query.sort().equals("VIEWS") && !Objects.equals(publication, result.publication())) {
        throw new CatalogException(409, "VIEW_PUBLICATION_CHANGED", "조회수가 갱신되었습니다. 목록을 새로고침해 주세요");
      }
      return result;
    } catch (CatalogException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw CatalogException.invalid();
    }
  }

  public String encode(
      SongQuery query, UUID publication, int rank, Long views, Instant publishedAt, UUID id) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            mapper.writeValueAsBytes(
                new Position(
                    1, hash(query), query.sort(), publication, rank, views, publishedAt, id)));
  }

  private String hash(SongQuery query) {
    String value =
        mapper.writeValueAsString(
            new QueryIdentity(
                query.text(),
                query.type(),
                query.memberIds(),
                query.year(),
                query.collaboration(),
                query.sort(),
                query.emptySearch(),
                query.requiredMember()));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private record QueryIdentity(
      String text,
      String type,
      java.util.List<UUID> members,
      Integer year,
      boolean collaboration,
      String sort,
      boolean emptySearch,
      UUID requiredMember) {}
}
