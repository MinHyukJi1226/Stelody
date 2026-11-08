package com.stelody.collector.domain;

import com.stelody.catalog.domain.SearchText;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

public final class PremierePublicationRules {
  public static final String VERSION = "premiere-credit-v1";
  private static final Pattern AMBIGUOUS =
      Pattern.compile(
          "(?iu)(?<![\\p{L}\\p{N}])(feat|ft|featuring|duet|collab|collaboration|medley|mashup|live|livestream|shorts?|teaser|trailer|x)(?![\\p{L}\\p{N}])|[&+×]|듀엣|합창|콜라보|메들리|쇼츠|생방송");

  public CoverPublicationRules.Result decide(
      VideoObservation video,
      DiscoveryRules.Decision type,
      List<CoverPublicationRules.Member> members,
      Instant now) {
    if (!"PUBLIC".equals(video.availability())
        || !"none".equals(video.liveBroadcastContent())
        || video.actualStartAt() == null
        || video.actualEndAt() == null
        || video.actualStartAt().isAfter(video.actualEndAt())
        || video.actualEndAt().isAfter(now)
        || video.publishedAt() == null
        || video.publishedAt().isAfter(now)
        || video.durationSeconds() == null
        || video.durationSeconds() <= 0
        || video.title() == null
        || video.title().isBlank()
        || video.title().length() > 300
        || SearchText.normalize(video.title()).length() > 400)
      return no("PREMIERE_COMPLETION_UNCONFIRMED");
    if (!"REVIEW".equals(type.disposition())) return no(type.reason());
    if ("COVER".equals(type.suggestedType()))
      return new CoverPublicationRules().participants(video.title(), members);
    if (!"ORIGINAL".equals(type.suggestedType())) return no("TYPE_UNCONFIRMED");
    String title = SearchText.normalize(video.title());
    if (AMBIGUOUS.matcher(title).find()) return no("PARTICIPANTS_UNCONFIRMED");
    var credited =
        members.stream()
            .filter(
                m ->
                    m.names().stream()
                        .anyMatch(
                            name ->
                                Pattern.compile(
                                        "(?<![\\p{L}\\p{N}])"
                                            + Pattern.quote(SearchText.normalize(name))
                                            + "(?![\\p{L}\\p{N}])")
                                    .matcher(title)
                                    .find()))
            .toList();
    return credited.size() == 1
        ? new CoverPublicationRules.Result(credited.getFirst(), "EXPLICIT_SOLO_ORIGINAL_CREDIT")
        : no("PARTICIPANTS_UNCONFIRMED");
  }

  private CoverPublicationRules.Result no(String reason) {
    return new CoverPublicationRules.Result(null, reason);
  }
}
