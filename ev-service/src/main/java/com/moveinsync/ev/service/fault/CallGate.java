package com.moveinsync.ev.service.fault;

import java.time.Clock;
import java.util.Objects;

/**
 * Counts consecutive failures and then fails fast. One call is allowed after the open interval.
 * Time O(1). Space O(1).
 */
public final class CallGate {

  private final int failureLimit;
  private final long openMillis;
  private final Clock clock;
  private int failures;
  private long openedAt;
  private boolean open;
  private boolean halfOpen;

  public CallGate(int failureLimit, long openMillis, Clock clock) {
    if (failureLimit <= 0 || openMillis <= 0L) {
      throw new IllegalArgumentException("gate bounds must be positive");
    }
    this.failureLimit = failureLimit;
    this.openMillis = openMillis;
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public synchronized void enter() {
    if (!open) {
      return;
    }
    if (halfOpen) {
      throw new CallBlocked();
    }
    if (clock.millis() - openedAt < openMillis) {
      throw new CallBlocked();
    }
    halfOpen = true;
  }

  public synchronized void success() {
    failures = 0;
    open = false;
    halfOpen = false;
  }

  public synchronized void failure() {
    if (halfOpen || failures + 1 >= failureLimit) {
      open = true;
      halfOpen = false;
      openedAt = clock.millis();
      failures = 0;
      return;
    }
    failures++;
  }
}
