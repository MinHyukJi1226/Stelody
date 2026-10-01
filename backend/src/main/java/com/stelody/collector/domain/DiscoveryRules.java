package com.stelody.collector.domain;

import java.util.regex.Pattern;

/** Suggestions only. No rule establishes participants or creates/publishes a song. */
public final class DiscoveryRules {
  public static final String VERSION = "title-v1";

  public record Decision(String disposition, String suggestedType, String reason, String version) {}

  private static final Pattern COVER =
      Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])cover(?:ed\\s+by)?(?![\\p{L}\\p{N}])|歌ってみた");
  private static final Pattern EXCLUDE =
      Pattern.compile(
          "(?iu)(?<![\\p{L}\\p{N}])(?:shorts|clip|clips|livestream)(?![\\p{L}\\p{N}])|\\[(?:클립|방송|다시보기)\\]");
  private static final Pattern ORIGINAL =
      Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])(?:original|mv)(?![\\p{L}\\p{N}])|오리지널");

  public Decision decide(VideoObservation video, boolean classificationAllowed) {
    if (!video.availability().equals("PUBLIC"))
      return new Decision("DEFERRED", "UNKNOWN", "NOT_PUBLIC", VERSION);
    if (!classificationAllowed)
      return new Decision("REVIEW", "UNKNOWN", "CLASSIFICATION_DISABLED", VERSION);
    String title = video.title() == null ? "" : video.title();
    if (EXCLUDE.matcher(title).find())
      return new Decision("EXCLUDED", "UNKNOWN", "EXPLICIT_EXCLUSION_MARKER", VERSION);
    if (COVER.matcher(title).find())
      return new Decision("REVIEW", "COVER", "COVER_REQUIRES_PARTICIPANT_REVIEW", VERSION);
    if (ORIGINAL.matcher(title).find())
      return new Decision("REVIEW", "ORIGINAL", "ORIGINAL_REQUIRES_REVIEW", VERSION);
    return new Decision("REVIEW", "UNKNOWN", "TYPE_UNCONFIRMED", VERSION);
  }
}
