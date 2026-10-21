package com.stelody.export.config;

import java.net.URI;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ExportSettings {
  public static final String SCOPE = "https://www.googleapis.com/auth/youtube.force-ssl";
  private final boolean enabled, workerEnabled;
  private final String key, redirectUri, localRedirectUri, frontendOrigin, apiUrl, revokeUri;

  public ExportSettings(Environment env) {
    enabled = env.getProperty("stelody.export.enabled", Boolean.class, false);
    workerEnabled = env.getProperty("stelody.export.worker-enabled", Boolean.class, true);
    key = env.getProperty("YOUTUBE_TOKEN_ENCRYPTION_KEY", "");
    redirectUri = env.getProperty("stelody.export.redirect-uri", "");
    localRedirectUri = env.getProperty("stelody.export.local-redirect-uri", "");
    frontendOrigin = env.getProperty("stelody.export.frontend-origin", "");
    if (!localRedirectUri.isEmpty()) {
      URI local = URI.create(localRedirectUri);
      if (!("http".equals(local.getScheme()) || "https".equals(local.getScheme()))
          || !("localhost".equals(local.getHost()) || "127.0.0.1".equals(local.getHost()))
          || local.getUserInfo() != null
          || local.getQuery() != null
          || local.getFragment() != null
          || !"/api/v1/me/youtube/callback/local".equals(local.getRawPath())
          || local.getPort() == 0
          || local.getPort() > 65535
          || !local.toASCIIString().equals(localRedirectUri))
        throw new IllegalArgumentException("Configure a loopback YouTube local callback");
    }
    if (!frontendOrigin.isEmpty()) {
      URI origin = URI.create(frontendOrigin);
      if (!"https".equals(origin.getScheme())
          || origin.getHost() == null
          || origin.getUserInfo() != null
          || origin.getQuery() != null
          || origin.getFragment() != null
          || !origin.getRawPath().isEmpty()
          || origin.getPort() == 0
          || origin.getPort() > 65535
          || !origin.toASCIIString().equals(frontendOrigin))
        throw new IllegalArgumentException("Configure an HTTPS frontend origin without a path");
    }
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

  public String callbackClient(String client) {
    if (client == null || client.equals("default")) return "default";
    if (client.equals("local") && !localRedirectUri.isEmpty()) return "local";
    throw new com.stelody.export.web.ExportException(400, "INVALID_YOUTUBE_CLIENT");
  }

  public String callbackUri(String client) {
    return callbackClient(client).equals("local") ? localRedirectUri : redirectUri;
  }

  public String frontendOrigin(String client) {
    if ("local".equals(client) && !localRedirectUri.isEmpty()) {
      URI local = URI.create(localRedirectUri);
      return local.getScheme() + "://" + local.getRawAuthority();
    }
    return frontendOrigin;
  }

  public String apiUrl() {
    return apiUrl;
  }

  public String revokeUri() {
    return revokeUri;
  }
}
