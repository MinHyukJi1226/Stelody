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

  public RuleConfiguration validated() {
    return new RuleConfiguration(validate(cover), validate(original), validate(exclude));
  }

  private static List<Marker> validate(List<Marker> markers) {
    if (markers == null || markers.size() > 30)
      throw new IllegalArgumentException("Invalid markers");
    var seen = new HashSet<String>();
    return markers.stream()
        .map(
            marker -> {
              if (marker == null
                  || marker.text() == null
                  || marker.match() == null
                  || marker.text().length() > 60)
                throw new IllegalArgumentException("Invalid marker");
              String text = SearchText.normalize(marker.text());
              if (text.isEmpty() || text.length() > 60 || !seen.add(text))
                throw new IllegalArgumentException("Invalid marker");
              return new Marker(text, marker.match());
            })
        .toList();
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
