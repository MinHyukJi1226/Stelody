package com.stelody.collector.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.core.env.Environment;

public record CollectorSettings(
    boolean enabled,
    String dbUrl,
    String dbUsername,
    String dbPassword,
    String apiKey,
    URI apiBase,
    Duration connectTimeout,
    Duration readTimeout,
    Duration maxRuntime,
    int maxAttempts,
    Duration retryDelay) {

  public static CollectorSettings from(Environment env) {
    return new CollectorSettings(
        env.getProperty("stelody.collector.enabled", Boolean.class, false),
        env.getProperty("stelody.collector.db-url", ""),
        env.getProperty("stelody.collector.db-username", ""),
        env.getProperty("stelody.collector.db-password", ""),
        env.getProperty("stelody.collector.api-key", ""),
        URI.create("https://www.googleapis.com/youtube/v3"),
        Duration.ofSeconds(5),
        Duration.ofSeconds(15),
        Duration.ofMinutes(10),
        3,
        Duration.ofSeconds(1));
  }

  public void validate() {
    if (!dbUrl.startsWith("jdbc:postgresql:")
        || dbUsername.isBlank()
        || dbPassword.isBlank()
        || apiKey.isBlank()
        || maxRuntime.isNegative()
        || maxRuntime.isZero()
        || maxRuntime.compareTo(Duration.ofMinutes(10)) > 0
        || maxAttempts < 1
        || maxAttempts > 5
        || connectTimeout.isNegative()
        || connectTimeout.isZero()
        || readTimeout.isNegative()
        || readTimeout.isZero()
        || retryDelay.isNegative())
      throw new IllegalArgumentException("COLLECTOR_CONFIGURATION_INVALID");
  }

  // Do not expose credentials through generated record logging.
  @Override
  public String toString() {
    return "CollectorSettings[enabled=" + enabled + "]";
  }
}
