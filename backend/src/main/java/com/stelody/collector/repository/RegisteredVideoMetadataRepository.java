package com.stelody.collector.repository;

import com.stelody.collector.domain.CollectionFailure;
import com.stelody.collector.domain.VideoObservation;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class RegisteredVideoMetadataRepository {
  public record Target(UUID id, String youtubeId, String channelId) {}

  private static final String CHANNEL_CONDITION =
      "NOT (c.collection_enabled AND c.channel_type IN ('GROUP', 'MEMBER'))";
  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

  public RegisteredVideoMetadataRepository(DataSource source) {
    jdbc = JdbcClient.create(source);
    transactions = new TransactionTemplate(new JdbcTransactionManager(source));
    transactions.setTimeout(10);
  }

  public List<Target> due(Instant now) {
    return jdbc.sql(
            """
            SELECT v.id,v.youtube_id,c.youtube_id FROM app.video v
            JOIN app.channel c ON c.id=v.channel_id
            WHERE %s
              AND (COALESCE(v.status_observed_at,v.source_observed_at) IS NULL
                OR COALESCE(v.status_observed_at,v.source_observed_at)<=:cutoff)
            ORDER BY COALESCE(v.status_observed_at,v.source_observed_at) NULLS FIRST,v.id LIMIT 50
            """
                .formatted(CHANNEL_CONDITION))
        .param("cutoff", Timestamp.from(now.minusSeconds(86400)))
        .query((r, n) -> new Target(r.getObject(1, UUID.class), r.getString(2), r.getString(3)))
        .list();
  }

  public void save(List<Target> targets, Map<String, VideoObservation> observations, Instant now) {
    for (var target : targets) {
      var value = observations.get(target.youtubeId());
      if (value == null
          || (value.channelId() != null && !target.channelId().equals(value.channelId())))
        throw new CollectionFailure("CHANNEL_MISMATCH", false);
    }
    transactions.executeWithoutResult(
        status -> {
          for (var target : targets) {
            var value = observations.get(target.youtubeId());
            var parameters =
                new MapSqlParameterSource()
                    .addValue("videoId", target.id())
                    .addValue("youtubeId", target.youtubeId())
                    .addValue("channelId", target.channelId())
                    .addValue("availability", value.availability())
                    .addValue("embeddable", value.embeddable())
                    .addValue("observedAt", Timestamp.from(now))
                    .addValue("title", value.title())
                    .addValue(
                        "publishedAt",
                        value.publishedAt() == null ? null : Timestamp.from(value.publishedAt()))
                    .addValue("thumbnail", value.thumbnailUrl())
                    .addValue("duration", value.durationSeconds());
            jdbc.sql(CollectionSql.UPDATE_REGISTERED_METADATA)
                .params(parameters.getValues())
                .update();
          }
        });
  }
}
