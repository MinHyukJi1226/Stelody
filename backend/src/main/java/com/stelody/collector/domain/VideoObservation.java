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
    String liveBroadcastContent,
    boolean liveStreamingDetailsPresent,
    Instant publicScheduledStartAt,
    Instant actualStartAt,
    Instant actualEndAt) {
  public VideoObservation(
      String youtubeId,
      String channelId,
      String availability,
      String title,
      Instant publishedAt,
      String thumbnailUrl,
      Long durationSeconds,
      boolean embeddable,
      Long viewCount,
      String liveBroadcastContent,
      boolean liveStreamingDetailsPresent,
      Instant publicScheduledStartAt) {
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
        liveBroadcastContent,
        liveStreamingDetailsPresent,
        publicScheduledStartAt,
        null,
        null);
  }

  public VideoObservation(
      String youtubeId,
      String channelId,
      String availability,
      String title,
      Instant publishedAt,
      String thumbnailUrl,
      Long durationSeconds,
      boolean embeddable,
      Long viewCount,
      String liveBroadcastContent,
      boolean liveStreamingDetailsPresent) {
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
        liveBroadcastContent,
        liveStreamingDetailsPresent,
        null);
  }

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
        null,
        false);
  }

  public static VideoObservation unavailable(String id) {
    return new VideoObservation(id, null, "UNAVAILABLE", null, null, null, null, false, null);
  }
}
