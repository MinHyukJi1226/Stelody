package com.stelody.collector.domain;

import com.stelody.catalog.domain.SearchText;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Conservative solo credits. Upload ownership is never participant evidence. */
public final class CoverPublicationRules {
  public static final String VERSION = "solo-credit-v1";

  public record Member(UUID id, long version, List<String> names) {}

  public record Result(Member member, String reason) {
    public boolean eligible() {
      return member != null;
    }
  }

  private static final Pattern AMBIGUOUS =
      Pattern.compile(
          "(?iu)(?<![\\p{L}\\p{N}])(shorts?|clips?|live|livestream|teaser|trailer|preview|medley|mashup|duet|feat|featuring|ft|collab|collaboration|chorus|reaction|relay|remix|instrumental|x)(?![\\p{L}\\p{N}])|[&+×]|쇼츠|클립|합창|콜라보|듀엣|메들리|다시보기|미리보기|歌枠|切り抜き|合唱");

  public Result decide(
      VideoObservation video,
      DiscoveryRules.Decision classification,
      List<Member> members,
      Instant now) {
    if (!classification.suggestedType().equals("COVER")
        || !classification.disposition().equals("REVIEW")) return no(classification.reason());
    if (video.title() == null
        || video.title().isBlank()
        || video.publishedAt() == null
        || video.channelId() == null) return no("REQUIRED_FIELDS_MISSING");
    if (video.publishedAt().isAfter(now)) return no("PUBLICATION_PENDING");
    if (!"none".equals(video.liveBroadcastContent())) return no("LIVE_STATUS_UNCONFIRMED");
    // Review short/unknown-length uploads; duration alone never excludes or labels a Short.
    if (video.durationSeconds() == null || video.durationSeconds() <= 180)
      return no("FULL_LENGTH_UNCONFIRMED");
    if (video.title().length() > 300 || SearchText.normalize(video.title()).length() > 400)
      return no("DISPLAY_TITLE_TOO_LONG");
    String title = SearchText.normalize(video.title());
    if (AMBIGUOUS.matcher(title).find()) return no("PARTICIPATION_OR_CONTENT_AMBIGUOUS");
    if (RuleConfiguration.matches(
        List.of(
            new RuleConfiguration.Marker("original", RuleConfiguration.Match.WORD),
            new RuleConfiguration.Marker("오리지널", RuleConfiguration.Match.PHRASE)),
        title)) return no("CONTENT_TYPE_CONFLICT");
    var mentioned =
        members.stream().filter(m -> m.names().stream().anyMatch(n -> contains(title, n))).toList();
    if (mentioned.size() != 1) return no("PARTICIPANTS_UNCONFIRMED");
    Member member = mentioned.getFirst();
    // Only complete suffix credits, including redundant Korean/English aliases of the same person.
    for (String name : member.names()) {
      String quoted = Pattern.quote(SearchText.normalize(name));
      String alternate =
          member.names().stream()
              .map(SearchText::normalize)
              .map(Pattern::quote)
              .reduce((a, b) -> a + "|" + b)
              .orElse(quoted);
      String credit = quoted + "(?:\\s*\\((?:" + alternate + ")\\))?";
      String segment = title.substring(title.lastIndexOf('/') + 1).strip();
      if (Pattern.compile(credit + "\\s+cover").matcher(segment).matches()
          || Pattern.compile("(?<![\\p{L}\\p{N}])covered\\s+by\\s+" + credit + "\\s*$")
              .matcher(title)
              .find()) return new Result(member, "EXPLICIT_SOLO_COVER_CREDIT");
    }
    return no("PARTICIPANTS_UNCONFIRMED");
  }

  private boolean contains(String title, String name) {
    String n = SearchText.normalize(name);
    return !n.isEmpty()
        && Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(n) + "(?![\\p{L}\\p{N}])")
            .matcher(title)
            .find();
  }

  private Result no(String reason) {
    return new Result(null, reason);
  }
}
