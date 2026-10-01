package com.stelody.collector.domain;

import java.time.Duration;
import java.util.function.LongSupplier;

public final class CollectionBudget {
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  private final LongSupplier nanos;
  private final long started;
  private final long allowed;

  public CollectionBudget(Duration duration) {
    this(duration, System::nanoTime);
  }

  public CollectionBudget(Duration duration, LongSupplier nanos) {
    this.nanos = nanos;
    this.started = nanos.getAsLong();
    this.allowed = duration.toNanos();
  }

  public Duration remaining() {
    long left = allowed - (nanos.getAsLong() - started);
    if (left <= 0 || Thread.currentThread().isInterrupted())
      throw new CollectionFailure("TIME_LIMIT", false);
    return Duration.ofNanos(left);
  }

  public void pause(Duration delay, Sleeper sleeper) {
    if (remaining().compareTo(delay) <= 0) throw new CollectionFailure("TIME_LIMIT", false);
    try {
      sleeper.sleep(delay);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new CollectionFailure("TIME_LIMIT", false);
    }
    remaining();
  }
}
