package com.stelody.collector.config;

import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;

public record DiscoveryOptions(
    boolean classificationAllowed,
    UUID channelId,
    boolean backfill,
    int maxPages,
    boolean autoPublicationPolicyAllowed) {
  public DiscoveryOptions(
      boolean classificationAllowed, UUID channelId, boolean backfill, int maxPages) {
    this(classificationAllowed, channelId, backfill, maxPages, false);
  }

  public DiscoveryOptions {
    if (maxPages < 1 || maxPages > 5 || (backfill && channelId == null))
      throw new IllegalArgumentException("Invalid discovery options");
  }

  public static DiscoveryOptions from(Environment env, ApplicationArguments args) {
    boolean backfill = args.containsOption("backfill");
    String channel = one(args, "channel", null);
    int pages = Integer.parseInt(one(args, "max-pages", backfill ? "1" : "3"));
    return new DiscoveryOptions(
        env.getProperty("DISCOVERY_CLASSIFICATION_ALLOWED", Boolean.class, false),
        channel == null ? null : UUID.fromString(channel),
        backfill,
        pages,
        env.getProperty("COVER_AUTO_PUBLICATION_POLICY_ALLOWED", Boolean.class, false));
  }

  private static String one(ApplicationArguments args, String name, String fallback) {
    if (!args.containsOption(name)) return fallback;
    var values = args.getOptionValues(name);
    if (values.size() != 1 || values.getFirst().isBlank())
      throw new IllegalArgumentException("Invalid discovery options");
    return values.getFirst();
  }
}
