package com.moveinsync.ev.planner.telemetry;

import com.moveinsync.ev.planner.solve.DriftEvent;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/** Last accepted reading for one cab. Not safe to share across threads. */
final class CabLink {

  private final String cabId;
  private boolean seen;
  private boolean hasExpected;
  private boolean triggered;
  private long lastSocWh;
  private long expectedSocWh;
  private Instant lastObserved = Instant.EPOCH;
  private Instant lastTrigger = Instant.EPOCH;

  CabLink(String cabId) {
    this.cabId = cabId;
  }

  Optional<DriftEvent> accept(
      long socWh, OptionalLong expected, Instant observedAt, TelemetryPolicy policy) {
    if (seen && observedAt.isBefore(lastObserved)) {
      return Optional.empty();
    }
    if (seen && implausible(socWh, observedAt, policy) && socWh >= policy.criticalSocWh()) {
      return Optional.empty();
    }
    lastSocWh = socWh;
    lastObserved = observedAt;
    seen = true;
    if (expected.isPresent()) {
      expectedSocWh = expected.orElseThrow();
      hasExpected = true;
    }
    return maybeDrift(socWh, observedAt, policy);
  }

  Optional<DriftEvent> silence(Instant now, TelemetryPolicy policy) {
    if (!seen) {
      return Optional.empty();
    }
    long elapsed = now.getEpochSecond() - lastObserved.getEpochSecond();
    if (elapsed < policy.silenceSeconds()) {
      return Optional.empty();
    }
    long estimated = decay(lastSocWh, elapsed, policy);
    lastSocWh = estimated;
    lastObserved = now;
    return maybeDrift(estimated, now, policy);
  }

  private Optional<DriftEvent> maybeDrift(long socWh, Instant observedAt, TelemetryPolicy policy) {
    if (!hasExpected) {
      return Optional.empty();
    }
    long gap = expectedSocWh > socWh ? expectedSocWh - socWh : socWh - expectedSocWh;
    if (gap < policy.hysteresisWh()) {
      return Optional.empty();
    }
    boolean cooled =
        triggered && observedAt.isBefore(lastTrigger.plusSeconds(policy.cooldownSeconds()));
    if (cooled && socWh >= policy.criticalSocWh()) {
      return Optional.empty();
    }
    lastTrigger = observedAt;
    triggered = true;
    return Optional.of(
        new DriftEvent(DriftEvent.Kind.BATTERY, cabId, observedAt, OptionalLong.of(socWh)));
  }

  private boolean implausible(long socWh, Instant observedAt, TelemetryPolicy policy) {
    long elapsed = observedAt.getEpochSecond() - lastObserved.getEpochSecond();
    if (elapsed < 0L) {
      return false;
    }
    long delta = socWh - lastSocWh;
    if (elapsed == 0L) {
      return delta != 0L;
    }
    if (delta > 0L) {
      return delta > product(policy.maxRiseWhPerSecond(), elapsed);
    }
    if (delta < 0L) {
      return -delta > product(policy.maxDropWhPerSecond(), elapsed);
    }
    return false;
  }

  private static long decay(long socWh, long elapsed, TelemetryPolicy policy) {
    long drop = product(policy.decayWhPerSecond(), elapsed);
    if (drop >= socWh) {
      return 0L;
    }
    return socWh - drop;
  }

  private static long product(long rate, long elapsed) {
    long cap = Long.MAX_VALUE / rate;
    long bounded = Math.min(elapsed, cap);
    return Math.multiplyExact(rate, bounded);
  }
}
