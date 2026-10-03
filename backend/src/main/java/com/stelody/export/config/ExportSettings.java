package com.stelody.export.config;

import java.net.URI;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ExportSettings {
  public static final String SCOPE = "https://www.googleapis.com/auth/youtube.force-ssl";
  private final boolean enabled, workerEnabled;
  private final String key, redirectUri, apiUrl, revokeUri;

  public ExportSettings(Environment env) {
    enabled = env.getProperty("stelody.export.enabled", Boolean.class, false);
    workerEnabled = env.getProperty("stelody.export.worker-enabled", Boolean.class, true);
    key = env.getProperty("YOUTUBE_TOKEN_ENCRYPTION_KEY", "");
    redirectUri = env.getProperty("stelody.export.redirect-uri", "");
    apiUrl = env.getProperty("stelody.export.api-url", "https://www.googleapis.com/youtube/v3");
    revokeUri =
        env.getProperty("stelody.export.revoke-uri", "https://oauth2.googleapis.com/revoke");
    if (enabled) {
      validate(redirectUri, !env.matchesProfiles("prod") && env.matchesProfiles("local", "test"));
      if (!"/api/v1/me/youtube/callback".equals(URI.create(redirectUri).getPath()))
        throw new IllegalArgumentException("Configure the YouTube callback path");
      validate(apiUrl, !env.matchesProfiles("prod") && env.matchesProfiles("test"));
      validate(revokeUri, !env.matchesProfiles("prod") && env.matchesProfiles("test"));
    }
  }

  private static void validate(String value, boolean allowLoopback) {
    URI uri = URI.create(value);
    boolean loopback =
        allowLoopback
            && "http".equals(uri.getScheme())
            && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
    if (uri.getHost() == null
        || !("https".equals(uri.getScheme()) || loopback)
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null)
      throw new IllegalArgumentException(
          "Configure an HTTPS endpoint (local callback may use loopback HTTP)");
  }

  public boolean enabled() {
    return enabled;
  }

  public boolean workerEnabled() {
    return workerEnabled;
  }

  public String key() {
    return key;
  }

  public String redirectUri() {
    return redirectUri;
  }

  public String apiUrl() {
    return apiUrl;
  }

  public String revokeUri() {
    return revokeUri;
  }
}
