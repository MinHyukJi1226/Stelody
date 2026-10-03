package com.stelody.statistics.service;

import com.stelody.catalog.web.CatalogException;
import com.stelody.song.repository.SongRepository;
import com.stelody.statistics.dto.StatisticsDtos.*;
import com.stelody.statistics.repository.StatisticsQueries;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
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

  public ViewStatisticsService(SongRepository songs, StatisticsQueries queries, Clock clock) {
    this.songs = songs;
    this.queries = queries;
    this.clock = clock;
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

  private CatalogException invalid() {
    return new CatalogException(400, "INVALID_STATISTICS_QUERY", "통계 조회 기간 또는 개수를 확인해 주세요");
  }
}
