package com.stelody.milestone;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;

class MilestonePolicyTest {
  private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");

  @Test
  void eachHundredThousandStepHasItsOwnFiveThousandWindow() {
    for (long goal : new long[] {100_000, 200_000, 1_000_000, 10_000_000}) {
      assertThat(MilestonePolicy.approaching(goal - 5001)).isFalse();
      assertThat(MilestonePolicy.approaching(goal - 5000)).isTrue();
      assertThat(MilestonePolicy.next(goal - 1)).isEqualTo(goal);
      assertThat(MilestonePolicy.approaching(goal)).isFalse();
      assertThat(MilestonePolicy.next(goal)).isEqualTo(goal + 100_000);
    }
  }

  @Test
  void estimateUsesActualElapsedTimeAndRecentGrowth() {
    assertThat(MilestonePolicy.estimate(980_000, NOW, 975_000L, NOW.minusSeconds(3600)))
        .isEqualTo(NOW.plusSeconds(14400));
    assertThat(MilestonePolicy.interval(NOW, NOW.plusSeconds(14400)))
        .isEqualTo(Duration.ofMinutes(10));
    assertThat(MilestonePolicy.interval(NOW, NOW.plusSeconds(900)))
        .isEqualTo(Duration.ofMinutes(1));
    assertThat(MilestonePolicy.interval(NOW, NOW.plusSeconds(21601)))
        .isEqualTo(Duration.ofHours(1));
  }

  @Test
  void absentFlatDecliningStaleAndInvalidBaselinesDoNotInventAnEta() {
    assertThat(MilestonePolicy.estimate(99_000, NOW, null, null)).isNull();
    for (long before : new long[] {99_000, 100_000, -1})
      assertThat(MilestonePolicy.estimate(99_000, NOW, before, NOW.minusSeconds(3600))).isNull();
    for (long age : new long[] {0, -1, 59, 21601})
      assertThat(MilestonePolicy.estimate(99_000, NOW, 90_000L, NOW.minusSeconds(age))).isNull();
    assertThat(MilestonePolicy.interval(NOW, null)).isEqualTo(Duration.ofHours(1));
  }

  @Test
  void arithmeticIsSafeNearBigintLimitAndDoesNotForecastBeyondThirtyDays() {
    assertThat(MilestonePolicy.next(Long.MAX_VALUE)).isNull();
    assertThat(MilestonePolicy.estimate(0, NOW, 0L, NOW.minusSeconds(3600))).isNull();
    assertThat(MilestonePolicy.estimate(1, NOW, 0L, NOW.minusSeconds(3600))).isNull();
    assertThatThrownBy(() -> MilestonePolicy.next(-1)).isInstanceOf(IllegalArgumentException.class);
  }
}
