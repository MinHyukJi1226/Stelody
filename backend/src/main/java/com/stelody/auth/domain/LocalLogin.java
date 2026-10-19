package com.stelody.auth.domain;

import jakarta.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.net.URI;
import java.time.Instant;

/** Selected from server configuration and carried only in the state-bound OAuth request. */
public record LocalLogin(String callbackUri, Instant expiresAt) implements Serializable {
  public static final String ATTRIBUTE = LocalLogin.class.getName();

  public String location(String path) {
    URI callback = URI.create(callbackUri);
    return callback.getScheme() + "://" + callback.getRawAuthority() + path;
  }

  public static String location(HttpServletRequest request, String path) {
    var local = (LocalLogin) request.getAttribute(ATTRIBUTE);
    return local == null ? path : local.location(path);
  }
}
