package com.stelody.statistics.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class StatisticsDtos {
  private StatisticsDtos() {}

  public record Point(LocalDate date, Long viewCount, Instant observedAt, String source) {}

  public record Views(
      UUID songId,
      UUID videoId,
      String youtubeId,
      String timeZone,
      int days,
      LocalDate startDate,
      LocalDate endDate,
      Instant collectionStartedAt,
      Long latestViewCount,
      Instant latestObservedAt,
      UUID publicationId,
      Instant publishedAt,
      List<Point> points) {}
}
