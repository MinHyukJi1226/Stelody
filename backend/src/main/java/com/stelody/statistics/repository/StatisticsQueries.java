package com.stelody.statistics.repository;

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
  public record Publication(UUID id, Instant referenceAt) {}

  private final JdbcClient jdbc;

  public StatisticsQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Publication publication() {
    return jdbc.sql(
            """
        SELECT p.id,p.published_at
        FROM app.catalog_state s LEFT JOIN app.view_publication p ON p.id=s.view_publication_id
        WHERE s.singleton
        """)
        .query((r, n) -> new Publication(r.getObject("id", UUID.class), instant(r, "published_at")))
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

  private static Instant instant(ResultSet r, String field) throws SQLException {
    Timestamp value = r.getTimestamp(field);
    return value == null ? null : value.toInstant();
  }
}
