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
    Long viewCount) {
  public static VideoObservation unavailable(String id) {
    return new VideoObservation(id, null, "UNAVAILABLE", null, null, null, null, false, null);
  }
}
