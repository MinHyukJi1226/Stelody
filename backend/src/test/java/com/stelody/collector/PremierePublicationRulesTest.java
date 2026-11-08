package com.stelody.collector;

import static org.assertj.core.api.Assertions.*;

import com.stelody.collector.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PremierePublicationRulesTest {
  private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
  private final List<CoverPublicationRules.Member> members =
      List.of(
          new CoverPublicationRules.Member(UUID.randomUUID(), 0, List.of("아오쿠모 린")),
          new CoverPublicationRules.Member(UUID.randomUUID(), 0, List.of("텐코 시부키")));

  private VideoObservation video(String title, Instant start, Instant end) {
    return new VideoObservation(
        "abcdefghijk",
        "UC" + "a".repeat(22),
        "PUBLIC",
        title,
        NOW.minusSeconds(500),
        null,
        200L,
        true,
        100L,
        "none",
        true,
        null,
        start,
        end);
  }

  private boolean eligible(String title, Instant start, Instant end) {
    var v = video(title, start, end);
    return new PremierePublicationRules()
        .decide(v, new DiscoveryRules().decide(v, true), members, NOW)
        .eligible();
  }

  @Test
  void noneStatusAloneOrFutureOrReversedTimesCannotConfirmCompletion() {
    String title = "노래 / 아오쿠모 린 Cover";
    assertThat(eligible(title, NOW.minusSeconds(300), null)).isFalse();
    assertThat(eligible(title, NOW.minusSeconds(300), NOW.plusSeconds(10))).isFalse();
    assertThat(eligible(title, NOW.minusSeconds(10), NOW.minusSeconds(300))).isFalse();
    assertThat(eligible(title, NOW.minusSeconds(300), NOW.minusSeconds(10))).isTrue();
  }

  @Test
  void unknownParticipantOrJointOriginalCreditsRequireReview() {
    assertThat(eligible("신곡 Original", NOW.minusSeconds(300), NOW.minusSeconds(10))).isFalse();
    assertThat(eligible("아오쿠모 린 & 텐코 시부키 Original", NOW.minusSeconds(300), NOW.minusSeconds(10)))
        .isFalse();
    assertThat(eligible("아오쿠모 린 feat. Guest Original", NOW.minusSeconds(300), NOW.minusSeconds(10)))
        .isFalse();
    assertThat(eligible("아오쿠모 린 신곡 Original", NOW.minusSeconds(300), NOW.minusSeconds(10)))
        .isTrue();
  }
}
