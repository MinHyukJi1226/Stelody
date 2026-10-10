package com.stelody.collector.domain;

import com.stelody.catalog.domain.SearchText;
import java.util.HashSet;
import java.util.List;

/** Literal keywords only; user-supplied regular expressions are never executed. */
public record RuleConfiguration(List<Marker> cover, List<Marker> original, List<Marker> exclude) {
  public enum Match {
    WORD,
    PHRASE
  }

  public record Marker(String text, Match match) {}

  public static final class InvalidInput extends IllegalArgumentException {
    private final String field;
    private final String code;

    public InvalidInput(String field, String code) {
      super("Invalid rule configuration");
      this.field = field;
      this.code = code;
    }

    public String field() {
      return field;
    }

    public String code() {
      return code;
    }
  }

  public RuleConfiguration validated() {
    return new RuleConfiguration(
        validate(cover, "cover"), validate(original, "original"), validate(exclude, "exclude"));
  }

  private static List<Marker> validate(List<Marker> markers, String path) {
    if (markers == null) throw new InvalidInput(path, "REQUIRED");
    if (markers.size() > 30) throw new InvalidInput(path, "INVALID_SIZE");
    var seen = new HashSet<String>();
    var result = new java.util.ArrayList<Marker>();
    for (int i = 0; i < markers.size(); i++) {
      var marker = markers.get(i);
      String field = path + "[" + i + "]";
      if (marker == null) throw new InvalidInput(field, "REQUIRED");
      if (marker.text() == null) throw new InvalidInput(field + ".text", "REQUIRED");
      if (marker.match() == null) throw new InvalidInput(field + ".match", "REQUIRED");
      String text = SearchText.normalize(marker.text());
      if (text.isEmpty()) throw new InvalidInput(field + ".text", "REQUIRED");
      if (marker.text().length() > 60 || text.length() > 60)
        throw new InvalidInput(field + ".text", "INVALID_SIZE");
      if (!seen.add(text)) throw new InvalidInput(field + ".text", "DUPLICATE");
      result.add(new Marker(text, marker.match()));
    }
    return List.copyOf(result);
  }

  public static RuleConfiguration defaults() {
    return new RuleConfiguration(
        List.of(word("cover"), word("covered by"), phrase("歌ってみた")),
        List.of(word("original"), word("mv"), phrase("오리지널")),
        List.of(
            word("shorts"),
            word("clip"),
            word("clips"),
            word("livestream"),
            phrase("[클립]"),
            phrase("[방송]"),
            phrase("[다시보기]")));
  }

  private static Marker word(String value) {
    return new Marker(value, Match.WORD);
  }

  private static Marker phrase(String value) {
    return new Marker(value, Match.PHRASE);
  }

  public static boolean matches(List<Marker> markers, String title) {
    for (var marker : markers) {
      int index = title.indexOf(marker.text());
      while (index >= 0) {
        int end = index + marker.text().length();
        if (marker.match() == Match.PHRASE
            || (index == 0 || !Character.isLetterOrDigit(title.codePointBefore(index)))
                && (end == title.length() || !Character.isLetterOrDigit(title.codePointAt(end))))
          return true;
        index = title.indexOf(marker.text(), index + 1);
      }
    }
    return false;
  }
}
