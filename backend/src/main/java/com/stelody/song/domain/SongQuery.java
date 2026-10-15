package com.stelody.song.domain;

import com.stelody.auth.web.ApiFieldError;
import com.stelody.catalog.domain.SearchText;
import com.stelody.catalog.web.CatalogException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
    UUID requiredMember,
    LocalDate startDate,
    LocalDate endDate) {
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
    return create(
        q, type, members, year, collaboration, sort, size, mode, requiredMember, null, null);
  }

  public static SongQuery create(
      String q,
      String type,
      List<UUID> members,
      Integer year,
      boolean collaboration,
      String sort,
      int size,
      String mode,
      UUID requiredMember,
      String startDate,
      String endDate) {
    var errors = new ArrayList<ApiFieldError>();
    var start = parseDate("startDate", startDate, errors);
    var end = parseDate("endDate", endDate, errors);
    if (start != null && end != null && start.isAfter(end)) {
      errors.add(new ApiFieldError("startDate", "INVALID_DATE_RANGE", "시작일은 종료일보다 늦을 수 없습니다"));
      errors.add(new ApiFieldError("endDate", "INVALID_DATE_RANGE", "종료일은 시작일보다 빠를 수 없습니다"));
    }
    if (year != null && (startDate != null || endDate != null)) {
      errors.add(new ApiFieldError("year", "CONFLICTING_FILTER", "연도와 날짜 범위는 함께 사용할 수 없습니다"));
      if (startDate != null)
        errors.add(
            new ApiFieldError("startDate", "CONFLICTING_FILTER", "연도와 날짜 범위는 함께 사용할 수 없습니다"));
      if (endDate != null)
        errors.add(new ApiFieldError("endDate", "CONFLICTING_FILTER", "연도와 날짜 범위는 함께 사용할 수 없습니다"));
    }
    if (!errors.isEmpty()) throw CatalogException.invalid(errors);
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
        requiredMember,
        start,
        end);
  }

  private static LocalDate parseDate(String field, String value, List<ApiFieldError> errors) {
    if (value == null) return null;
    try {
      if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
        throw new DateTimeParseException("format", "", 0);
      var date = LocalDate.parse(value);
      if (date.getYear() < 1900 || date.getYear() > 2100) {
        errors.add(new ApiFieldError(field, "DATE_OUT_OF_RANGE", "날짜 연도는 1900~2100이어야 합니다"));
        return null;
      }
      return date;
    } catch (DateTimeParseException exception) {
      errors.add(new ApiFieldError(field, "INVALID_DATE", "YYYY-MM-DD 형식의 유효한 날짜를 입력해 주세요"));
      return null;
    }
  }

  public Instant rangeStart() {
    return startDate == null ? null : startDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
  }

  public Instant rangeEndExclusive() {
    return endDate == null
        ? null
        : endDate.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
  }

  public Instant yearStart() {
    return LocalDate.of(year, 1, 1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
  }

  public Instant yearEnd() {
    return LocalDate.of(year + 1, 1, 1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
  }
}
