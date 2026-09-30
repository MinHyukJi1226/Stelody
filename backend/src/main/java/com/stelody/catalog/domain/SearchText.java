package com.stelody.catalog.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class SearchText {
  private static final Pattern WHITESPACE =
      Pattern.compile("[\\s\\p{Z}]+", Pattern.UNICODE_CHARACTER_CLASS);

  private SearchText() {}

  public static String normalize(String value) {
    if (value == null) return "";
    return WHITESPACE
        .matcher(Normalizer.normalize(value, Normalizer.Form.NFKC))
        .replaceAll(" ")
        .strip()
        .toLowerCase(Locale.ROOT);
  }

  public static String likePattern(String normalized) {
    return "%" + normalized.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }
}
