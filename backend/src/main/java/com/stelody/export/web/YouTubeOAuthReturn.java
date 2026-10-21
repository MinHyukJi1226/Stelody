package com.stelody.export.web;

import com.stelody.auth.domain.LoginReturn;
import com.stelody.export.config.ExportSettings;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/** Redirect destinations come only from configuration and a consumed OAuth request. */
@Component
public class YouTubeOAuthReturn {
  public static final String DEFAULT_PATH = "/library/youtube";
  public static final String CALLBACK = "/api/v1/me/youtube/callback";
  public static final String LOCAL_CALLBACK = CALLBACK + "/local";
  private static final Set<String> RESERVED =
      Set.of(
          "youtuberesult",
          "youtubeerror",
          "code",
          "state",
          "error",
          "error_description",
          "access_token",
          "refresh_token",
          "id_token");
  private final ExportSettings settings;

  public YouTubeOAuthReturn(ExportSettings settings) {
    this.settings = settings;
  }

  public static boolean isCallback(HttpServletRequest request) {
    return request.getMethod().equals("GET")
        && Set.of(CALLBACK, LOCAL_CALLBACK)
            .contains(request.getRequestURI().substring(request.getContextPath().length()));
  }

  public static String path(String value) {
    try {
      return value == null ? DEFAULT_PATH : LoginReturn.validate(value);
    } catch (IllegalArgumentException e) {
      throw new ExportException(400, "INVALID_YOUTUBE_RETURN");
    }
  }

  public String location(String client, String path, String result, String errorCode) {
    // Even malformed/expired local callbacks return to the configured local frontend.
    String origin = settings.frontendOrigin(client);
    var builder = UriComponentsBuilder.fromUriString(path(path));
    builder.build(true).getQueryParams().keySet().stream()
        .filter(
            k ->
                RESERVED.contains(
                    UriUtils.decode(k, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT)))
        .forEach(builder::replaceQueryParam);
    builder.queryParam("youtubeResult", result);
    if (errorCode != null) builder.queryParam("youtubeError", errorCode);
    return origin + builder.build(true).toUriString();
  }
}
