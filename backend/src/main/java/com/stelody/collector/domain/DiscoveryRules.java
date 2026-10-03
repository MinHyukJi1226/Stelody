package com.stelody.collector.domain;

import com.stelody.catalog.domain.SearchText;

/** Content suggestions; CoverPublicationRules independently verifies solo credits. */
public final class DiscoveryRules {
  public static final String VERSION = "title-v1";
  private final RuleConfiguration configuration;
  private final String version;

  public record Decision(String disposition, String suggestedType, String reason, String version) {}

  public DiscoveryRules() {
    this(RuleConfiguration.defaults(), VERSION);
  }

  public DiscoveryRules(RuleConfiguration configuration, String version) {
    this.configuration = configuration.validated();
    this.version = version;
  }

  public Decision decide(VideoObservation video, boolean classificationAllowed) {
    if (!video.availability().equals("PUBLIC"))
      return decision("DEFERRED", "UNKNOWN", "NOT_PUBLIC");
    if (!classificationAllowed) return decision("REVIEW", "UNKNOWN", "CLASSIFICATION_DISABLED");
    String title = SearchText.normalize(video.title() == null ? "" : video.title());
    if (RuleConfiguration.matches(configuration.exclude(), title))
      return decision("EXCLUDED", "UNKNOWN", "EXPLICIT_EXCLUSION_MARKER");
    if (RuleConfiguration.matches(configuration.cover(), title))
      return decision("REVIEW", "COVER", "COVER_REQUIRES_PARTICIPANT_REVIEW");
    if (RuleConfiguration.matches(configuration.original(), title))
      return decision("REVIEW", "ORIGINAL", "ORIGINAL_REQUIRES_REVIEW");
    return decision("REVIEW", "UNKNOWN", "TYPE_UNCONFIRMED");
  }

  public String version() {
    return version;
  }

  private Decision decision(String disposition, String type, String reason) {
    return new Decision(disposition, type, reason, version);
  }
}
