package com.stelody.schedule;

import com.stelody.collector.domain.DiscoveryRules;
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
  @Schema(
      requiredProperties = {
        "youtubeId",
        "title",
        "channelName",
        "scheduledStartAt",
        "url",
        "thumbnailUrl"
      })
  public record Item(
      String youtubeId,
      String title,
      String channelName,
      Instant scheduledStartAt,
      String url,
      @Schema(nullable = true, description = "YouTube 원본 썸네일 URL. 확인된 이미지가 없으면 null.")
          String thumbnailUrl) {}

  public record Target(String youtubeId, UUID channelId, String youtubeChannelId) {}

  private final JdbcClient jdbc;

  public PublicScheduleRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<Item> upcoming() {
    return jdbc.sql(
            """
        SELECT p.youtube_id,p.title,c.name,p.scheduled_start_at,p.thumbnail_url
        FROM app.youtube_public_schedule p JOIN app.channel c ON c.id=p.channel_id
        WHERE c.collection_enabled AND c.channel_type IN ('GROUP','MEMBER')
          AND p.song_type IN ('COVER','ORIGINAL')
          AND p.actual_start_at IS NULL AND NOT EXISTS (SELECT 1 FROM app.video v WHERE v.youtube_id=p.youtube_id)
          AND NOT EXISTS (SELECT 1 FROM app.review_item r
            WHERE r.youtube_id=p.youtube_id AND r.review_status<>'PENDING')
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
                    "https://www.youtube.com/watch?v=" + r.getString(1),
                    r.getString(5)))
        .list();
  }

  public void observe(
      UUID channelId, String youtubeChannelId, VideoObservation video, Instant now) {
    observe(
        channelId,
        youtubeChannelId,
        video,
        new DiscoveryRules().classifyTitle(video.title(), true),
        now);
  }

  public void observe(
      UUID channelId,
      String youtubeChannelId,
      VideoObservation video,
      DiscoveryRules.Decision classification,
      Instant now) {
    Instant start = video.publicScheduledStartAt();
    String type =
        classification.disposition().equals("REVIEW")
                && List.of("COVER", "ORIGINAL").contains(classification.suggestedType())
            ? classification.suggestedType()
            : null;
    if (start == null
        && "PUBLIC".equals(video.availability())
        && youtubeChannelId.equals(video.channelId())) {
      jdbc.sql(
              """
          UPDATE app.youtube_public_schedule SET title=:title,thumbnail_url=:thumbnail,
            observed_at=:now,song_type=:type,actual_start_at=:started,actual_end_at=:ended
          WHERE youtube_id=:id AND channel_id=:channel AND observed_at<=:now
          """)
          .param("title", video.title())
          .param("thumbnail", video.thumbnailUrl())
          .param("now", Timestamp.from(now))
          .param("type", type)
          .param(
              "started",
              video.actualStartAt() == null ? null : Timestamp.from(video.actualStartAt()))
          .param("ended", video.actualEndAt() == null ? null : Timestamp.from(video.actualEndAt()))
          .param("id", video.youtubeId())
          .param("channel", channelId)
          .update();
      return;
    }
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
        INSERT INTO app.youtube_public_schedule(
          youtube_id,channel_id,title,scheduled_start_at,observed_at,thumbnail_url,song_type)
        SELECT :id,c.id,:title,:start,:now,:thumbnail,:type FROM app.channel c
        WHERE c.id=:channel AND c.youtube_id=:youtubeChannel AND c.collection_enabled
          AND c.channel_type IN ('GROUP','MEMBER')
        ON CONFLICT(youtube_id) DO UPDATE SET channel_id=EXCLUDED.channel_id,title=EXCLUDED.title,
          scheduled_start_at=EXCLUDED.scheduled_start_at,observed_at=EXCLUDED.observed_at,
          thumbnail_url=EXCLUDED.thumbnail_url,song_type=EXCLUDED.song_type,
          actual_start_at=NULL,actual_end_at=NULL
        WHERE youtube_public_schedule.observed_at<=EXCLUDED.observed_at
        """)
        .param("id", video.youtubeId())
        .param("channel", channelId)
        .param("youtubeChannel", youtubeChannelId)
        .param("title", video.title())
        .param("start", Timestamp.from(start))
        .param("now", Timestamp.from(now))
        .param("type", type)
        .param("thumbnail", video.thumbnailUrl())
        .update();
  }

  public List<Target> due(Instant now) {
    return jdbc.sql(
            """
        SELECT p.youtube_id,p.channel_id,c.youtube_id
        FROM app.youtube_public_schedule p JOIN app.channel c ON c.id=p.channel_id
        WHERE c.collection_enabled AND c.channel_type IN ('GROUP','MEMBER')
          AND p.observed_at<=:cutoff
          AND NOT EXISTS (SELECT 1 FROM app.video v WHERE v.youtube_id=p.youtube_id)
          AND NOT EXISTS (SELECT 1 FROM app.review_item r
            WHERE r.youtube_id=p.youtube_id AND r.review_status<>'PENDING')
        ORDER BY p.observed_at,p.scheduled_start_at,p.youtube_id LIMIT 50
        """)
        .param("cutoff", Timestamp.from(now.minusSeconds(60)))
        .query((r, n) -> new Target(r.getString(1), r.getObject(2, UUID.class), r.getString(3)))
        .list();
  }

  public void cleanup(Instant now) {
    jdbc.sql(
            """
        DELETE FROM app.youtube_public_schedule p
        WHERE p.observed_at<=:oldest
          OR NOT EXISTS (SELECT 1 FROM app.channel c WHERE c.id=p.channel_id
                         AND c.collection_enabled AND c.channel_type IN ('GROUP','MEMBER'))
        """)
        .param("oldest", Timestamp.from(now.minusSeconds(30L * 86400)))
        .update();
  }
}
