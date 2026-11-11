package com.stelody.collector.domain;

import com.stelody.review.domain.OriginalCredits;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public final class CollectedSongTitle {
  private static final Pattern ORIGINAL_CREDIT =
      Pattern.compile(
          "^(?<title>.+?)\\s*\\[\\s*(?<original>[^\\[\\]/]+?)\\s+/\\s+(?<artist>[^\\[\\]/]+?)\\s*]$");

  public record Credit(String title, String original, List<String> artists) {}

  private CollectedSongTitle() {}

  public static String display(String source, CoverPublicationRules.Member member, boolean cover) {
    String title =
        OriginalCredits.text(source)
            .replaceAll(
                "(?iu)[\\[【(](?:official (?:mv|music video|audio)|music video|mv|audio|4k|8k|hd|cover|original|오리지널)[\\]】)]",
                "")
            .strip();
    if (cover) {
      String withoutMember = withoutCoverCredit(title, member.names());
      return originalCredit(withoutMember).map(Credit::title).orElse(withoutMember);
    }
    String credit = memberCredit(member.names());
    return title
        .replaceFirst("(?iu)^" + credit + "\\s+[-–—]\\s+", "")
        .replaceFirst("(?iu)\\s*/\\s*" + credit + "(?:\\s+(?:original|오리지널))?$", "")
        .replaceFirst("(?iu)\\s+(?:original|오리지널)$", "")
        .strip();
  }

  public static String withoutCoverCredit(String title, List<String> names) {
    if (names.isEmpty()) return title;
    String credit = memberCredit(names);
    return title
        .replaceFirst(
            "(?iu)\\s*(?:/\\s*"
                + credit
                + "\\s+cover|(?<![\\p{L}\\p{N}])covered\\s+by\\s+"
                + credit
                + ")\\s*$",
            "")
        .strip();
  }

  private static String memberCredit(List<String> memberNames) {
    String names =
        memberNames.stream()
            .map(OriginalCredits::text)
            .filter(name -> !name.isEmpty())
            .map(Pattern::quote)
            .reduce((a, b) -> a + "|" + b)
            .orElseThrow();
    return "(?:" + names + ")(?:\\s*\\((?:" + names + ")\\))?";
  }

  public static Optional<Credit> originalCredit(String title) {
    var match = ORIGINAL_CREDIT.matcher(title);
    if (!match.matches()) return Optional.empty();
    var artists = OriginalCredits.artistNames(match.group("artist"));
    if (artists.isEmpty()) return Optional.empty();
    return Optional.of(
        new Credit(match.group("title").strip(), match.group("original").strip(), artists));
  }
}
