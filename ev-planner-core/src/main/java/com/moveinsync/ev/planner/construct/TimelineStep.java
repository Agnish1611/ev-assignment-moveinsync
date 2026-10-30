package com.moveinsync.ev.planner.construct;

import java.time.Instant;
import java.util.Objects;

/**
 * One step on a cab timeline. SoC is watt-hours at the start and end of the step. A deadhead uses
 * the distance provider's duration even when the published assignment stores that duration as 0.
 */
public record TimelineStep(
    Kind kind,
    String subjectId,
    Instant start,
    Instant end,
    long socAtStartWh,
    long socAtEndWh,
    int distanceMeters) {

  /** What the cab is doing during the step. */
  public enum Kind {
    DEADHEAD,
    TRIP,
    CHARGE
  }

  public TimelineStep {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(subjectId, "subjectId must not be null");
    Objects.requireNonNull(start, "start must not be null");
    Objects.requireNonNull(end, "end must not be null");
    if (subjectId.isBlank() || subjectId.contains("@") || subjectId.startsWith("+")) {
      throw new IllegalArgumentException("subjectId must be an opaque identifier");
    }
    if (start.isAfter(end)) {
      throw new IllegalArgumentException("start must not be after end");
    }
    if (socAtStartWh < 0L || socAtEndWh < 0L) {
      throw new IllegalArgumentException("SoC must be non-negative");
    }
    if (distanceMeters < 0) {
      throw new IllegalArgumentException("distanceMeters must be non-negative: " + distanceMeters);
    }
  }
}
