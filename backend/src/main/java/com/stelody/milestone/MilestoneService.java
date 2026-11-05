package com.stelody.milestone;

import com.stelody.catalog.repository.PublicCatalogSql;
import com.stelody.song.dto.SongDtos;
import com.stelody.song.repository.SongRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class MilestoneService {
  private final JdbcClient jdbc;
  private final SongRepository songs;
  private final Clock clock;
  private final boolean enabled;

  public MilestoneService(
      JdbcClient jdbc,
      SongRepository songs,
      Clock clock,
      @Value("${stelody.milestones.enabled:false}") boolean enabled) {
    this.jdbc = jdbc;
    this.songs = songs;
    this.clock = clock;
    this.enabled = enabled;
  }

  private record Entry(UUID song, UUID video, long views, Instant observed, long highest) {}

  private record Event(UUID song, long target, Instant at) {}

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public MilestoneDtos.Feed feed() {
    Instant now = clock.instant();
    if (!enabled)
      return new MilestoneDtos.Feed(MilestoneDtos.Status.DISABLED, now, List.of(), List.of());
    String eligible =
        PublicCatalogSql.SONGS
            + """
      SELECT s.id,m.video_id,m.view_count,m.observed_at,m.highest_target FROM public_songs s
      JOIN app.video v ON v.id=s.representative_video_id
      JOIN app.channel c ON c.id=v.channel_id AND c.collection_enabled AND c.channel_type IN('MEMBER','GROUP')
      JOIN app.view_milestone_state m ON m.video_id=v.id
      WHERE m.available AND m.observed_at>=:oldest AND m.observed_at<=:now
      ORDER BY s.id
      """;
    var entries =
        jdbc.sql(eligible)
            .param("now", Timestamp.from(now))
            .param("oldest", Timestamp.from(now.minusSeconds(7200)))
            .query(
                (r, n) ->
                    new Entry(
                        r.getObject(1, UUID.class),
                        r.getObject(2, UUID.class),
                        r.getLong(3),
                        r.getTimestamp(4).toInstant(),
                        r.getLong(5)))
            .list();
    if (entries.isEmpty())
      return new MilestoneDtos.Feed(MilestoneDtos.Status.PENDING, now, List.of(), List.of());
    var events =
        jdbc.sql(
                "SELECT video_id,target,detected_at FROM app.view_milestone_event WHERE video_id IN(:videos) AND detected_at>=:oldest AND detected_at<=:now ORDER BY detected_at DESC,target DESC,video_id LIMIT 10")
            .param("videos", entries.stream().map(Entry::video).toList())
            .param("oldest", Timestamp.from(now.minusSeconds(86400)))
            .param("now", Timestamp.from(now))
            .query(
                (r, n) ->
                    new Event(
                        r.getObject(1, UUID.class), r.getLong(2), r.getTimestamp(3).toInstant()))
            .list();
    var byVideo = new HashMap<UUID, UUID>();
    entries.forEach(e -> byVideo.put(e.video(), e.song()));
    var selected = new LinkedHashSet<UUID>();
    entries.stream()
        .filter(
            e ->
                MilestonePolicy.approaching(e.views())
                    && MilestonePolicy.next(e.views()) > e.highest())
        .forEach(e -> selected.add(e.song()));
    events.forEach(e -> selected.add(byVideo.get(e.song())));
    var cards = new HashMap<UUID, SongDtos.Card>();
    if (!selected.isEmpty())
      songs
          .cards(songs.findAll(List.copyOf(selected), songs.publication()))
          .forEach(c -> cards.put(c.id(), c));
    var approaching =
        entries.stream()
            .filter(
                e ->
                    MilestonePolicy.approaching(e.views())
                        && MilestonePolicy.next(e.views()) > e.highest())
            .sorted(
                Comparator.comparingLong((Entry e) -> MilestonePolicy.next(e.views()) - e.views())
                    .thenComparing(Entry::song))
            .map(
                e ->
                    new MilestoneDtos.Approaching(
                        cards.get(e.song()),
                        MilestonePolicy.next(e.views()),
                        e.views(),
                        e.observed()))
            .toList();
    var achievements =
        events.stream()
            .map(
                e ->
                    new MilestoneDtos.Achievement(
                        cards.get(byVideo.get(e.song())), e.target(), e.at()))
            .toList();
    return new MilestoneDtos.Feed(MilestoneDtos.Status.READY, now, approaching, achievements);
  }
}
