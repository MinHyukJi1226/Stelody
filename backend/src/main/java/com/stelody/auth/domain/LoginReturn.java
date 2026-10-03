package com.stelody.auth.domain;

import java.io.Serializable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/** Stored only inside the server-side OAuth request, never read from callback parameters. */
public record LoginReturn(String path, Instant expiresAt) implements Serializable {
  public static final String ATTRIBUTE = LoginReturn.class.getName();

  public static String validate(String value) {
    if (value == null
        || value.isEmpty()
        || value.length() > 2048
        || !value.startsWith("/")
        || value.startsWith("//")
        || value.chars().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c))
        || value.indexOf('\\') >= 0)
      throw new IllegalArgumentException("Invalid login return path");
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid login return path");
    }
    String path = uri.getPath(), rawPath = uri.getRawPath();
    // Block encoded separators and nested path encodings before browser/server normalization.
    if (uri.isAbsolute()
        || uri.getRawAuthority() != null
        || path == null
        || rawPath.toLowerCase(Locale.ROOT).matches(".*%(2f|5c).*"))
      throw new IllegalArgumentException("Invalid login return path");
    String lower = path.toLowerCase(Locale.ROOT);
    if (path.indexOf('%') >= 0
        || path.indexOf(';') >= 0
        || path.indexOf('\\') >= 0
        || path.chars().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c))
        || Arrays.stream(path.split("/", -1)).anyMatch(s -> s.equals(".") || s.equals(".."))
        || lower.equals("/api")
        || lower.startsWith("/api/")
        || lower.equals("/actuator")
        || lower.startsWith("/actuator/"))
      throw new IllegalArgumentException("Invalid login return path");
    String encoded = uri.toASCIIString();
    if (encoded.length() > 2048) throw new IllegalArgumentException("Invalid login return path");
    return encoded;
  }

  public String location(String result) {
    var builder = UriComponentsBuilder.fromUriString(validate(path));
    builder.build(true).getQueryParams().keySet().stream()
        .filter(name -> UriUtils.decode(name, StandardCharsets.UTF_8).equals("loginResult"))
        .forEach(name -> builder.replaceQueryParam(name));
    return builder.queryParam("loginResult", result).build(true).toUriString();
  }
}
