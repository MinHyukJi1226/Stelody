package com.stelody.collector.scheduling;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Uses persisted completion, so a process restart does not reset the collection schedule. */
public final class CollectionScheduleState {
  public record Plan(boolean videos, boolean discovery, boolean retries) {
    public boolean due() {
      return videos || discovery || retries;
    }
  }

  private final JdbcClient jdbc;
  private final boolean discoveryEnabled;
  private final boolean retryEnabled;

  public CollectionScheduleState(
      DataSource source, boolean discoveryEnabled, boolean retryEnabled) {
    jdbc = JdbcClient.create(source);
    this.discoveryEnabled = discoveryEnabled;
    this.retryEnabled = retryEnabled;
  }

  public Plan due(Instant now) {
    Instant hour = now.truncatedTo(ChronoUnit.HOURS);
    boolean videos =
        jdbc.sql(
                """
            SELECT NOT EXISTS (SELECT 1 FROM app.collection_run
              WHERE logical_slot=:slot AND status='SUCCEEDED')
              OR EXISTS (SELECT 1 FROM app.collection_run
              WHERE logical_slot>=:oldest AND logical_slot<=:slot AND status<>'SUCCEEDED')
            """)
            .param("slot", Timestamp.from(hour))
            .param("oldest", Timestamp.from(now.minusSeconds(172800)))
            .query(Boolean.class)
            .single();
    boolean discovery =
        discoveryEnabled
            && jdbc.sql(
                    """
            SELECT EXISTS (SELECT 1 FROM app.channel c
              LEFT JOIN app.discovery_channel_state s ON s.channel_id=c.id
              WHERE c.collection_enabled AND c.channel_type IN ('GROUP','MEMBER')
                AND (s.in_progress OR s.last_scanned_at IS NULL OR s.last_scanned_at<:since))
            """)
                .param("since", Timestamp.from(discoveryBoundary(now)))
                .query(Boolean.class)
                .single();
    boolean retries =
        retryEnabled
            && jdbc.sql(
                    """
            SELECT EXISTS (SELECT 1 FROM app.collection_retry_request
              WHERE status IN ('QUEUED','RUNNING'))
            """)
                .query(Boolean.class)
                .single();
    return new Plan(videos, discovery, retries);
  }

  /** Latest midnight in Korea, including when the server starts later in the day. */
  public static Instant discoveryBoundary(Instant now) {
    var zone = ZoneId.of("Asia/Seoul");
    return now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
  }
}
