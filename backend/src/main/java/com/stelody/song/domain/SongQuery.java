package com.stelody.song.domain;

import com.stelody.catalog.domain.SearchText;
import com.stelody.catalog.web.CatalogException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public record SongQuery(
    String text,
    String type,
    List<UUID> memberIds,
    Integer year,
    boolean collaboration,
    String sort,
    int size,
    boolean emptySearch,
    UUID requiredMember) {
  public static SongQuery create(
      String q,
      String type,
      List<UUID> members,
      Integer year,
      boolean collaboration,
      String sort,
      int size,
      String mode,
      UUID requiredMember) {
    var normalized = SearchText.normalize(q);
    String actualMode = mode == null ? (q == null ? "BROWSE" : "SEARCH") : mode;
    String actualSort = sort == null ? (normalized.isEmpty() ? "LATEST" : "RELEVANCE") : sort;
    String actualType = type == null ? "ALL" : type;
    var ids = members == null ? List.<UUID>of() : members.stream().distinct().sorted().toList();
    if ((q != null && q.length() > 200)
        || normalized.length() > 200
        || ids.size() > 20
        || size < 1
        || size > 50
        || (year != null && (year < 1900 || year > 2100))
        || !List.of("BROWSE", "SEARCH").contains(actualMode)
        || !List.of("ALL", "COVER", "ORIGINAL").contains(actualType)
        || !List.of("LATEST", "RELEVANCE", "VIEWS").contains(actualSort)
        || (actualMode.equals("BROWSE") && !normalized.isEmpty())) throw CatalogException.invalid();
    return new SongQuery(
        normalized,
        actualType,
        ids,
        year,
        collaboration,
        actualSort,
        size,
        actualMode.equals("SEARCH") && normalized.isEmpty(),
        requiredMember);
  }

  public Instant yearStart() {
    return LocalDate.of(year, 1, 1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
  }

  public Instant yearEnd() {
    return LocalDate.of(year + 1, 1, 1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
  }
}
