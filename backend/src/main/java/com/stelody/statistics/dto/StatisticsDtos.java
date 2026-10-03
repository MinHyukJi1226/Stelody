package com.stelody.statistics.dto;

import com.stelody.song.dto.SongDtos;
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

  public record RisingItem(
      int rank,
      SongDtos.Card song,
      long increase,
      long startViewCount,
      long endViewCount,
      Instant startObservedAt,
      Instant endObservedAt) {}

  public record Trending(
      String status,
      String metricSource,
      int periodHours,
      int toleranceMinutes,
      UUID publicationId,
      Instant referenceAt,
      List<RisingItem> items) {}
}
