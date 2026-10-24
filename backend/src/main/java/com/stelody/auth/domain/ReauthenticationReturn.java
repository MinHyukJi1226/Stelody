package com.stelody.auth.domain;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/** Only state-bound server metadata selects a frontend destination. */
public record ReauthenticationReturn(String path, String client, String callbackUri)
    implements Serializable {
  public static final String ATTRIBUTE = ReauthenticationReturn.class.getName();
  public static final String ERROR_ATTRIBUTE = ATTRIBUTE + ".error";
  public static final String DEFAULT_PATH = "/api/v1/me/withdrawal-confirmation";
  public static final String CALLBACK = "/api/v1/auth/callback/google-reauthentication";
  public static final String LOCAL_CALLBACK = CALLBACK + "-local";
  private static final Set<String> RESERVED =
      Set.of(
          "reauthresult",
          "reautherror",
          "code",
          "state",
          "error",
          "error_description",
          "access_token",
          "refresh_token",
          "id_token");

  public enum Error {
    SESSION_EXPIRED,
    REAUTHENTICATION_REQUEST_INVALID,
    REAUTHENTICATION_REQUEST_EXPIRED,
    REAUTHENTICATION_ACCOUNT_MISMATCH,
    GOOGLE_REAUTHENTICATION_CANCELLED,
    GOOGLE_REAUTHENTICATION_FAILED,
    ACCOUNT_STORAGE_UNAVAILABLE
  }

  public String location(String origin, String result, Error error) {
    var builder =
        UriComponentsBuilder.fromUriString(
            path == null ? DEFAULT_PATH : LoginReturn.validate(path));
    builder.build(true).getQueryParams().keySet().stream()
        .filter(
            k ->
                RESERVED.contains(
                    UriUtils.decode(k, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT)))
        .forEach(builder::replaceQueryParam);
    builder.queryParam("reauthResult", result);
    if (error != null) builder.queryParam("reauthError", error.name());
    return origin + builder.build(true).toUriString();
  }
}
