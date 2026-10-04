package com.stelody.statistics.dto;

import com.stelody.song.dto.SongDtos;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class StatisticsDtos {
  private StatisticsDtos() {}

  @Schema(requiredProperties = {"date", "viewCount", "observedAt", "source"})
  public record Point(
      LocalDate date,
      @Schema(nullable = true) Long viewCount,
      @Schema(nullable = true) Instant observedAt,
      @Schema(nullable = true) String source) {}

  @Schema(
      requiredProperties = {
        "songId",
        "videoId",
        "youtubeId",
        "timeZone",
        "days",
        "startDate",
        "endDate",
        "collectionStartedAt",
        "latestViewCount",
        "latestObservedAt",
        "publicationId",
        "publishedAt",
        "points"
      })
  public record Views(
      UUID songId,
      UUID videoId,
      String youtubeId,
      String timeZone,
      int days,
      LocalDate startDate,
      LocalDate endDate,
      @Schema(nullable = true) Instant collectionStartedAt,
      @Schema(nullable = true) Long latestViewCount,
      @Schema(nullable = true) Instant latestObservedAt,
      @Schema(nullable = true) UUID publicationId,
      @Schema(nullable = true) Instant publishedAt,
      List<Point> points) {}

  @Schema(
      requiredProperties = {
        "rank",
        "song",
        "increase",
        "startViewCount",
        "endViewCount",
        "startObservedAt",
        "endObservedAt"
      })
  public record RisingItem(
      int rank,
      SongDtos.Card song,
      long increase,
      long startViewCount,
      long endViewCount,
      Instant startObservedAt,
      Instant endObservedAt) {}

  @Schema(
      requiredProperties = {
        "status",
        "metricSource",
        "periodHours",
        "toleranceMinutes",
        "publicationId",
        "referenceAt",
        "items"
      })
  public record Trending(
      String status,
      String metricSource,
      int periodHours,
      int toleranceMinutes,
      @Schema(nullable = true) UUID publicationId,
      @Schema(nullable = true) Instant referenceAt,
      List<RisingItem> items) {}
}
