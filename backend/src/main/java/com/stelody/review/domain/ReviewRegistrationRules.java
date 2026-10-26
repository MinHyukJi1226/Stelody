package com.stelody.review.domain;

import com.stelody.admin.dto.CatalogAdminDtos.*;
import com.stelody.catalog.domain.SearchText;
import com.stelody.collector.domain.*;
import com.stelody.collector.repository.CollectionRuleStore.Snapshot;
import com.stelody.review.dto.ReviewDtos;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Builds editable drafts from observed data, without granting new publication authority. */
public final class ReviewRegistrationRules {
  public static final String REASON = "신규 검토 후보 선택 일괄 등록";
  private final CoverPublicationRules credits = new CoverPublicationRules();

  public record Plan(SongInput song, VideoKind kind) {}

  public static final class Unresolved extends RuntimeException {
    private final String reason;

    public Unresolved(String reason) {
      this.reason = reason;
    }

    public String reason() {
      return reason;
    }
  }

  public Plan plan(
      ReviewDtos.Item source,
      Channel channel,
      List<CoverPublicationRules.Member> members,
      Snapshot configuration) {
    var observation =
        new VideoObservation(
            source.youtubeId(),
            channel.youtubeId(),
            source.availability(),
            source.title(),
            source.publishedAt(),
            source.thumbnailUrl(),
            source.durationSeconds(),
            false,
            null);
    var classified = configuration.rules().decide(observation, true);
    if (!"REVIEW".equals(classified.disposition())) throw new Unresolved("CONTENT_REQUIRES_REVIEW");
    String type = source.suggestedType();
    if ("UNKNOWN".equals(type)) type = classified.suggestedType();
    else if (!"UNKNOWN".equals(classified.suggestedType())
        && !type.equals(classified.suggestedType())) throw new Unresolved("CONTENT_TYPE_CONFLICT");
    if (!Set.of("COVER", "ORIGINAL").contains(type)) throw new Unresolved("TYPE_UNCONFIRMED");

    String title = display(source.title());
    List<UUID> memberIds = List.of();
    if ("COVER".equals(type)) {
      var credit = credits.participants(source.title(), members);
      if (credit.eligible()
          && (!"MEMBER".equals(channel.channelType())
              || credit.member().id().equals(channel.memberId()))) {
        memberIds = List.of(credit.member().id());
        title = withoutCredit(title, credit.member());
      }
    }
    if (title.isBlank() || title.length() > 300 || SearchText.normalize(title).length() > 400)
      throw new Unresolved("TITLE_REQUIRES_EDIT");
    VideoKind kind = "COVER".equals(type) ? VideoKind.OFFICIAL_COVER : originalKind(title);
    // Stored candidates lack durable broadcast metadata required by CoverPublicationRules.
    // Selecting candidates is registration approval, not a new auto-publication policy.
    var song =
        new SongInput(
            0L,
            title,
            SongType.valueOf(type),
            null,
            Visibility.DRAFT,
            null,
            List.of(),
            memberIds,
            List.of(),
            false,
            null,
            SearchVisibility.UNCHECKED,
            null,
            List.of(),
            List.of(),
            REASON);
    return new Plan(song, kind);
  }

  private String display(String value) {
    return value == null
        ? ""
        : Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replaceAll("(?U)[\\s\\p{Z}]+", " ")
            .strip();
  }

  private String withoutCredit(String title, CoverPublicationRules.Member member) {
    String names =
        member.names().stream()
            .map(this::display)
            .map(Pattern::quote)
            .reduce((a, b) -> a + "|" + b)
            .orElseThrow();
    String credit = "(?:" + names + ")(?:\\s*\\((?:" + names + ")\\))?";
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

  private VideoKind originalKind(String title) {
    String value = SearchText.normalize(title);
    boolean mv =
        RuleConfiguration.matches(
            List.of(
                new RuleConfiguration.Marker("mv", RuleConfiguration.Match.WORD),
                new RuleConfiguration.Marker("music video", RuleConfiguration.Match.WORD),
                new RuleConfiguration.Marker("뮤직비디오", RuleConfiguration.Match.PHRASE)),
            value);
    boolean audio =
        RuleConfiguration.matches(
            List.of(
                new RuleConfiguration.Marker("audio", RuleConfiguration.Match.WORD),
                new RuleConfiguration.Marker("음원", RuleConfiguration.Match.PHRASE)),
            value);
    return mv == audio ? VideoKind.OTHER : mv ? VideoKind.OFFICIAL_MV : VideoKind.AUDIO;
  }
}
