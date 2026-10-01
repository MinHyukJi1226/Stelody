package com.stelody.collector.repository;

final class CollectionSql {
  private CollectionSql() {}

  static final String UPDATE_VIDEO =
      """
      UPDATE app.video v SET availability = :availability, embeddable = :embeddable,
        status_observed_at = :observedAt, source_title = COALESCE(:title, source_title),
        source_published_at = CASE WHEN CAST(:title AS text) IS NULL THEN source_published_at ELSE :publishedAt END,
        source_thumbnail_url = CASE WHEN CAST(:title AS text) IS NULL THEN source_thumbnail_url ELSE :thumbnail END,
        source_duration_seconds = CASE WHEN CAST(:title AS text) IS NULL THEN source_duration_seconds ELSE :duration END,
        source_observed_at = CASE WHEN CAST(:title AS text) IS NULL THEN source_observed_at ELSE :observedAt END
      WHERE v.id = :videoId AND v.youtube_id = :youtubeId
        AND (v.status_observed_at IS NULL OR v.status_observed_at <= :observedAt)
        AND EXISTS (SELECT 1 FROM app.channel c WHERE c.id = v.channel_id AND c.youtube_id = :channelId
          AND c.collection_enabled AND c.channel_type IN ('GROUP', 'MEMBER'))
      """;
}
