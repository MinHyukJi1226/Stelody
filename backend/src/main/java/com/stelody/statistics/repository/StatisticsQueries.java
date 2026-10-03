package com.stelody.statistics.repository;

import com.stelody.catalog.repository.PublicCatalogSql;
import com.stelody.statistics.dto.StatisticsDtos.Point;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StatisticsQueries {
  public record Publication(UUID id, Instant referenceAt, boolean successful) {}

  public record Growth(
      UUID songId, long startViews, long endViews, Instant startAt, Instant endAt) {
    public long increase() {
      return endViews - startViews;
    }
  }

  private final JdbcClient jdbc;

  public StatisticsQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Publication publication() {
    return jdbc.sql(
            """
        SELECT p.id,p.published_at,
          EXISTS (SELECT 1 FROM app.collection_run r WHERE r.publication_id=p.id
            AND r.status='SUCCEEDED') AS successful
        FROM app.catalog_state s LEFT JOIN app.view_publication p ON p.id=s.view_publication_id
        WHERE s.singleton
        """)
        .query(
            (r, n) ->
                new Publication(
                    r.getObject("id", UUID.class),
                    instant(r, "published_at"),
                    r.getBoolean("successful")))
        .single();
  }

  public Instant collectionStartedAt(UUID video) {
    return jdbc.sql("SELECT view_collection_started_at FROM app.video WHERE id=:id")
        .param("id", video)
        .query((r, n) -> new CollectionStart(instant(r, "view_collection_started_at")))
        .single()
        .at();
  }

  private record CollectionStart(Instant at) {}

  public List<Point> daily(UUID video, LocalDate oldest, Instant now) {
    return jdbc.sql(
            """
        SELECT day,view_count,observed_at,source FROM app.daily_video_view
        WHERE video_id=:id AND day>=:oldest AND observed_at>=:retentionStart AND observed_at<=:now
        ORDER BY day
        """)
        .param("id", video)
        .param("oldest", oldest)
        .param("retentionStart", Timestamp.from(now.minusSeconds(30L * 86400)))
        .param("now", Timestamp.from(now))
        .query(
            (r, n) ->
                new Point(
                    r.getObject("day", LocalDate.class),
                    r.getLong("view_count"),
                    instant(r, "observed_at"),
                    r.getString("source")))
        .list();
  }

  // Use a completed publication for V(T). A partially failed collection cannot
  // replace it. V(T-24h) is the nearest actual observation at or before that target.
  public List<Growth> rising(UUID publication, Instant reference, int size) {
    return jdbc.sql(
            PublicCatalogSql.SONGS
                + """
        SELECT s.id,previous.view_count AS start_views,current.view_count AS end_views,
          previous.observed_at AS start_at,current.observed_at AS end_at
        FROM public_songs s
        JOIN app.published_video_view current ON current.video_id=s.representative_video_id
          AND current.publication_id=:publication
        JOIN LATERAL (
          SELECT v.view_count,v.observed_at FROM app.view_snapshot v
          WHERE v.video_id=s.representative_video_id
            AND v.observed_at>=:previousOldest AND v.observed_at<=:previousTarget
          ORDER BY v.observed_at DESC,v.logical_slot DESC LIMIT 1
        ) previous ON true
        WHERE current.observed_at>=:currentOldest AND current.observed_at<=:reference
          AND current.view_count>previous.view_count
        ORDER BY current.view_count-previous.view_count DESC,s.published_at DESC,s.id DESC
        LIMIT :size
        """)
        .param("publication", publication)
        .param("reference", Timestamp.from(reference))
        .param("currentOldest", Timestamp.from(reference.minusSeconds(3600)))
        .param("previousTarget", Timestamp.from(reference.minusSeconds(86400)))
        .param("previousOldest", Timestamp.from(reference.minusSeconds(90000)))
        .param("size", size)
        .query(
            (r, n) ->
                new Growth(
                    r.getObject("id", UUID.class),
                    r.getLong("start_views"),
                    r.getLong("end_views"),
                    instant(r, "start_at"),
                    instant(r, "end_at")))
        .list();
  }

  private static Instant instant(ResultSet r, String field) throws SQLException {
    Timestamp value = r.getTimestamp(field);
    return value == null ? null : value.toInstant();
  }
}
