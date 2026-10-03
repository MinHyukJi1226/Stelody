package com.stelody.collector;

import static org.assertj.core.api.Assertions.*;

import com.stelody.collector.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CoverPublicationRulesTest {
  static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");
  final CoverPublicationRules rules = new CoverPublicationRules();
  final CoverPublicationRules.Member member =
      new CoverPublicationRules.Member(new UUID(0, 1), 0, List.of("아오쿠모 린", "Aokumo Rin"));

  VideoObservation video(String title, Long duration, String live, Instant published) {
    return new VideoObservation(
        "abcdefghijk",
        "UC" + "a".repeat(22),
        "PUBLIC",
        title,
        published,
        null,
        duration,
        true,
        10L,
        live,
        false);
  }

  CoverPublicationRules.Result decide(
      VideoObservation video, List<CoverPublicationRules.Member> members) {
    return rules.decide(video, new DiscoveryRules().decide(video, true), members, NOW);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "노래 / 아오쿠모 린 Cover",
        "노래 / AOKUMO RIN cover",
        "노래 / 아오쿠모 린 (Aokumo Rin) Cover",
        "노래 Covered by 아오쿠모 린"
      })
  void explicitSoloCreditAndAliasesConfirmOneMember(String title) {
    var result = decide(video(title, 201L, "none", NOW), List.of(member));
    assertThat(result.member()).isEqualTo(member);
    assertThat(result.reason()).isEqualTo("EXPLICIT_SOLO_COVER_CREDIT");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "노래 Cover",
        "아오쿠모 린의 채널 노래 Cover",
        "아오쿠모 린 Cover #Shorts",
        "노래 / guest & 아오쿠모 린 Cover",
        "노래 / Guest 아오쿠모 린 Cover",
        "Original / 아오쿠모 린 Cover",
        "노래 / guest x 아오쿠모 린 Cover",
        "노래 feat. guest / 아오쿠모 린 Cover",
        "노래 합창 / 아오쿠모 린 Cover",
        "노래 Live / 아오쿠모 린 Cover",
        "노래 / 아오쿠모 린 Cover teaser",
        "Discovery / 아오쿠모 린"
      })
  void ambiguousOrExcludedContentNeverPublishes(String title) {
    assertThat(decide(video(title, 240L, "none", NOW), List.of(member)).eligible()).isFalse();
  }

  @Test
  void multipleMembersAndDuplicateAliasesRequireReview() {
    var other = new CoverPublicationRules.Member(new UUID(0, 2), 0, List.of("유즈하 리코"));
    assertThat(
            decide(video("유즈하 리코 / 아오쿠모 린 Cover", 240L, "none", NOW), List.of(member, other))
                .eligible())
        .isFalse();
    var collision = new CoverPublicationRules.Member(new UUID(0, 3), 0, List.of("Aokumo Rin"));
    assertThat(
            decide(video("노래 / Aokumo Rin Cover", 240L, "none", NOW), List.of(member, collision))
                .eligible())
        .isFalse();
  }

  @Test
  void shortUnknownLengthLiveAndFuturePublicationRemainReviewOrDeferred() {
    for (Long duration : Arrays.asList(null, 0L, 30L, 180L))
      assertThat(
              decide(video("노래 / 아오쿠모 린 Cover", duration, "none", NOW), List.of(member)).reason())
          .isEqualTo("FULL_LENGTH_UNCONFIRMED");
    for (String live : Arrays.asList(null, "live", "upcoming"))
      assertThat(decide(video("노래 / 아오쿠모 린 Cover", 240L, live, NOW), List.of(member)).eligible())
          .isFalse();
    assertThat(
            decide(video("노래 / 아오쿠모 린 Cover", 240L, "none", NOW.plusSeconds(1)), List.of(member))
                .reason())
        .isEqualTo("PUBLICATION_PENDING");
  }

  @Test
  void completedBroadcastIsNotEligibleEvenWithNoneAndExplicitSoloCredit() {
    var v =
        new VideoObservation(
            "abcdefghijk",
            "UC" + "a".repeat(22),
            "PUBLIC",
            "노래 / 아오쿠모 린 Cover",
            NOW,
            null,
            240L,
            true,
            10L,
            "none",
            true);
    var result = decide(v, List.of(member));
    assertThat(result.eligible()).isFalse();
    assertThat(result.reason()).isEqualTo("LIVE_BROADCAST_METADATA_PRESENT");
  }

  @Test
  void policyDisabledClassificationAndMissingMembersCannotPublish() {
    var v = video("노래 / 아오쿠모 린 Cover", 240L, "none", NOW);
    assertThat(
            rules.decide(v, new DiscoveryRules().decide(v, false), List.of(member), NOW).eligible())
        .isFalse();
    assertThat(decide(v, List.of()).eligible()).isFalse();
  }
}
