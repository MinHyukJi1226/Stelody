package com.stelody.milestone;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

public final class MilestonePolicy {
  public static final long STEP = 100_000;
  public static final long APPROACHING = 5_000;

  private MilestonePolicy() {}

  public static long reached(long views) {
    if (views < 0) throw new IllegalArgumentException("NEGATIVE_VIEWS");
    return views / STEP * STEP;
  }

  public static Long next(long views) {
    long reached = reached(views);
    return reached > Long.MAX_VALUE - STEP ? null : reached + STEP;
  }

  public static boolean approaching(long views) {
    Long next = next(views);
    return next != null && next - views <= APPROACHING;
  }

  // Only observed counts are used. A correction, stale baseline or missing growth has no ETA.
  public static Instant estimate(long views, Instant at, Long previous, Instant previousAt) {
    Long target = next(views);
    if (target == null
        || previous == null
        || previousAt == null
        || previous < 0
        || views <= previous
        || !previousAt.isBefore(at)) return null;
    long elapsed = Duration.between(previousAt, at).toSeconds();
    if (elapsed < 60 || elapsed > 6 * 3600) return null;
    var seconds =
        BigDecimal.valueOf(target - views)
            .multiply(BigDecimal.valueOf(elapsed))
            .divide(BigDecimal.valueOf(views - previous), 0, RoundingMode.CEILING);
    if (seconds.compareTo(BigDecimal.valueOf(30L * 86400)) > 0) return null;
    return at.plusSeconds(Math.max(1, seconds.longValueExact()));
  }

  public static Duration interval(Instant at, Instant estimate) {
    if (estimate == null || estimate.isAfter(at.plusSeconds(6 * 3600))) return Duration.ofHours(1);
    if (estimate.isAfter(at.plusSeconds(15 * 60))) return Duration.ofMinutes(10);
    return Duration.ofMinutes(1);
  }
}
