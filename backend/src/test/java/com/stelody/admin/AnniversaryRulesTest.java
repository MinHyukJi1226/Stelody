package com.stelody.admin;

import static org.assertj.core.api.Assertions.*;

import com.stelody.admin.domain.AnniversaryRules;
import com.stelody.admin.domain.AnniversaryRules.Participant;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnniversaryRulesTest {
  static final UUID MEMBER = new UUID(0, 1), VIDEO = new UUID(0, 2);

  @Test
  void koreaDateIsUsedAndAllReasonsAreKept() {
    var result =
        AnniversaryRules.match(
            Instant.parse("2026-10-02T15:00:00Z"),
            VIDEO,
            List.of(new Participant(MEMBER, "member", 10, 3, LocalDate.of(2024, 10, 3))));
    assertThat(result).hasSize(2);
    assertThat(result)
        .extracting(e -> e.reason())
        .containsExactly("BIRTHDAY_DATE_MATCH", "DEBUT_DATE_MATCH");
    assertThat(result)
        .allSatisfy(e -> assertThat(e.publishedDate()).isEqualTo(LocalDate.of(2026, 10, 3)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"2025-02-28T03:00:00Z", "2025-03-01T03:00:00Z", "2024-02-28T03:00:00Z"})
  void leapDayHasNoReplacementDate(String published) {
    assertThat(
            AnniversaryRules.match(
                Instant.parse(published),
                VIDEO,
                List.of(new Participant(MEMBER, "member", 2, 29, LocalDate.of(2020, 2, 29)))))
        .isEmpty();
  }

  @Test
  void actualLeapDayMatches() {
    assertThat(
            AnniversaryRules.match(
                Instant.parse("2024-02-29T03:00:00Z"),
                VIDEO,
                List.of(new Participant(MEMBER, "member", 2, 29, null))))
        .hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"2024-10-03", "2026-10-03"})
  void sameDayOrFutureDebutIsNotAnAnniversary(String debut) {
    assertThat(
            AnniversaryRules.match(
                Instant.parse("2024-10-03T03:00:00Z"),
                VIDEO,
                List.of(new Participant(MEMBER, "member", null, null, LocalDate.parse(debut)))))
        .isEmpty();
  }

  @Test
  void missingDatesNeverInferCandidates() {
    assertThat(
            AnniversaryRules.match(
                Instant.now(), VIDEO, List.of(new Participant(MEMBER, "member", null, null, null))))
        .isEmpty();
  }
}
