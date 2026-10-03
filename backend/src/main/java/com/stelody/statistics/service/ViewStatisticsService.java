package com.stelody.statistics.service;

import com.stelody.catalog.web.CatalogException;
import com.stelody.song.repository.SongRepository;
import com.stelody.statistics.dto.StatisticsDtos.*;
import com.stelody.statistics.repository.StatisticsQueries;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ViewStatisticsService {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private final SongRepository songs;
  private final StatisticsQueries queries;
  private final Clock clock;
  private final boolean trendingEnabled, policyAllowed;

  public ViewStatisticsService(
      SongRepository songs,
      StatisticsQueries queries,
      Clock clock,
      @Value("${stelody.statistics.trending-enabled:false}") boolean trendingEnabled,
      @Value("${stelody.statistics.trending-policy-allowed:false}") boolean policyAllowed) {
    this.songs = songs;
    this.queries = queries;
    this.clock = clock;
    this.trendingEnabled = trendingEnabled;
    this.policyAllowed = policyAllowed;
  }

  public Views views(UUID id, int days) {
    if (days != 7 && days != 30) throw invalid();
    var now = clock.instant();
    var publication = queries.publication();
    var song = songs.find(id, publication.id()).orElseThrow(CatalogException::missing);
    var end = LocalDate.ofInstant(now, KST);
    var start = end.minusDays(days - 1);
    var points = queries.daily(song.videoId(), start, now);
    var byDate = new HashMap<LocalDate, Point>();
    points.forEach(p -> byDate.put(p.date(), p));
    var series = new ArrayList<Point>();
    for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1))
      series.add(byDate.getOrDefault(day, new Point(day, null, null, null)));
    boolean fresh =
        song.observedAt() != null
            && !song.observedAt().isAfter(now)
            && !song.observedAt().isBefore(now.minusSeconds(30L * 86400));
    return new Views(
        id,
        song.videoId(),
        song.youtubeId(),
        KST.getId(),
        days,
        start,
        end,
        queries.collectionStartedAt(song.videoId()),
        fresh ? song.viewCount() : null,
        fresh ? song.observedAt() : null,
        publication.id(),
        publication.referenceAt(),
        List.copyOf(series));
  }

  public Trending trending(int size) {
    if (size < 1 || size > 50) throw invalid();
    if (!trendingEnabled || !policyAllowed) return empty("DISABLED", null, null);
    var publication = queries.publication();
    if (publication.id() == null || !publication.successful()) return empty("PENDING", null, null);
    Instant reference = publication.referenceAt(), now = clock.instant();
    if (reference.isAfter(now) || reference.isBefore(now.minusSeconds(3600)))
      return empty("STALE", publication.id(), reference);
    var growth = queries.rising(publication.id(), reference, size);
    var rows =
        songs.findAll(
            growth.stream().map(StatisticsQueries.Growth::songId).toList(), publication.id());
    var cards = new HashMap<UUID, com.stelody.song.dto.SongDtos.Card>();
    songs.cards(rows).forEach(c -> cards.put(c.id(), c));
    var items = new ArrayList<RisingItem>();
    for (var value : growth)
      items.add(
          new RisingItem(
              items.size() + 1,
              cards.get(value.songId()),
              value.increase(),
              value.startViews(),
              value.endViews(),
              value.startAt(),
              value.endAt()));
    return new Trending(
        "READY", "STELODY_VIEW_GROWTH", 24, 60, publication.id(), reference, List.copyOf(items));
  }

  private Trending empty(String status, UUID publication, Instant reference) {
    return new Trending(status, "STELODY_VIEW_GROWTH", 24, 60, publication, reference, List.of());
  }

  private CatalogException invalid() {
    return new CatalogException(400, "INVALID_STATISTICS_QUERY", "통계 조회 기간 또는 개수를 확인해 주세요");
  }
}
