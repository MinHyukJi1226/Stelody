package com.stelody.schedule;

import com.stelody.collector.domain.VideoObservation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PublicScheduleRepository {
  @Schema(requiredProperties = {"youtubeId", "title", "channelName", "scheduledStartAt", "url"})
  public record Item(
      String youtubeId, String title, String channelName, Instant scheduledStartAt, String url) {}

  public record Target(String youtubeId, UUID channelId, String youtubeChannelId) {}

  private final JdbcClient jdbc;

  public PublicScheduleRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<Item> upcoming() {
    return jdbc.sql(
            """
        SELECT p.youtube_id,p.title,c.name,p.scheduled_start_at
        FROM app.youtube_public_schedule p JOIN app.channel c ON c.id=p.channel_id
        WHERE c.collection_enabled AND c.channel_type IN ('GROUP','MEMBER')
          AND p.scheduled_start_at>CURRENT_TIMESTAMP
          AND p.observed_at BETWEEN CURRENT_TIMESTAMP-interval '2 hours' AND CURRENT_TIMESTAMP
        ORDER BY p.scheduled_start_at,p.youtube_id LIMIT 50
        """)
        .query(
            (r, n) ->
                new Item(
                    r.getString(1),
                    r.getString(2),
                    r.getString(3),
                    r.getTimestamp(4).toInstant(),
                    "https://www.youtube.com/watch?v=" + r.getString(1)))
        .list();
  }

  public void observe(
      UUID channelId, String youtubeChannelId, VideoObservation video, Instant now) {
    Instant start = video.publicScheduledStartAt();
    if (start == null
        || !start.isAfter(now)
        || !youtubeChannelId.equals(video.channelId())
        || video.title() == null
        || video.title().isBlank()) {
      jdbc.sql(
              """
          DELETE FROM app.youtube_public_schedule
          WHERE youtube_id=:id AND channel_id=:channel AND observed_at<=:now
          """)
          .param("id", video.youtubeId())
          .param("channel", channelId)
          .param("now", Timestamp.from(now))
          .update();
      return;
    }
    jdbc.sql(
            """
        INSERT INTO app.youtube_public_schedule(youtube_id,channel_id,title,scheduled_start_at,observed_at)
        SELECT :id,c.id,:title,:start,:now FROM app.channel c
        WHERE c.id=:channel AND c.youtube_id=:youtubeChannel AND c.collection_enabled
          AND c.channel_type IN ('GROUP','MEMBER')
        ON CONFLICT(youtube_id) DO UPDATE SET channel_id=EXCLUDED.channel_id,title=EXCLUDED.title,
          scheduled_start_at=EXCLUDED.scheduled_start_at,observed_at=EXCLUDED.observed_at
        WHERE youtube_public_schedule.observed_at<=EXCLUDED.observed_at
        """)
        .param("id", video.youtubeId())
        .param("channel", channelId)
        .param("youtubeChannel", youtubeChannelId)
        .param("title", video.title())
        .param("start", Timestamp.from(start))
        .param("now", Timestamp.from(now))
        .update();
  }

  public List<Target> due(Instant now) {
    return jdbc.sql(
            """
        SELECT p.youtube_id,p.channel_id,c.youtube_id
        FROM app.youtube_public_schedule p JOIN app.channel c ON c.id=p.channel_id
        WHERE c.collection_enabled AND c.channel_type IN ('GROUP','MEMBER')
          AND p.observed_at<=:cutoff
        ORDER BY p.observed_at,p.scheduled_start_at,p.youtube_id LIMIT 50
        """)
        .param("cutoff", Timestamp.from(now.minusSeconds(45 * 60)))
        .query((r, n) -> new Target(r.getString(1), r.getObject(2, UUID.class), r.getString(3)))
        .list();
  }

  public void cleanup(Instant now) {
    jdbc.sql(
            """
        DELETE FROM app.youtube_public_schedule p
        WHERE p.observed_at<=:oldest OR p.scheduled_start_at<=:elapsed
          OR NOT EXISTS (SELECT 1 FROM app.channel c WHERE c.id=p.channel_id
                         AND c.collection_enabled AND c.channel_type IN ('GROUP','MEMBER'))
        """)
        .param("oldest", Timestamp.from(now.minusSeconds(30L * 86400)))
        .param("elapsed", Timestamp.from(now.minusSeconds(3600)))
        .update();
  }
}
