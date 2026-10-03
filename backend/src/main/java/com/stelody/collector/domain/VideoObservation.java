package com.stelody.collector.domain;

import java.time.Instant;

public record VideoObservation(
    String youtubeId,
    String channelId,
    String availability,
    String title,
    Instant publishedAt,
    String thumbnailUrl,
    Long durationSeconds,
    boolean embeddable,
    Long viewCount,
    String liveBroadcastContent) {
  public VideoObservation(
      String youtubeId,
      String channelId,
      String availability,
      String title,
      Instant publishedAt,
      String thumbnailUrl,
      Long durationSeconds,
      boolean embeddable,
      Long viewCount) {
    this(
        youtubeId,
        channelId,
        availability,
        title,
        publishedAt,
        thumbnailUrl,
        durationSeconds,
        embeddable,
        viewCount,
        null);
  }

  public static VideoObservation unavailable(String id) {
    return new VideoObservation(id, null, "UNAVAILABLE", null, null, null, null, false, null);
  }
}
