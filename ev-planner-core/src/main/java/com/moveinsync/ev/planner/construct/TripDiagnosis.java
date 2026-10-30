package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Why one backup trip did not fit, including the plug window that would have made it fit.
 *
 * @param hubId hub whose capacity was binding, when a single extra plug would accept the trip
 * @param windowStart start of that counterfactual session, UTC
 * @param windowEnd end of that counterfactual session, UTC
 */
public record TripDiagnosis(
    String tripId,
    UnassignedReasonCode reasonCode,
    String explanation,
    Optional<String> hubId,
    Optional<Instant> windowStart,
    Optional<Instant> windowEnd,
    List<CabFinding> findings) {

  public TripDiagnosis {
    Objects.requireNonNull(tripId, "tripId must not be null");
    Objects.requireNonNull(reasonCode, "reasonCode must not be null");
    Objects.requireNonNull(explanation, "explanation must not be null");
    Objects.requireNonNull(hubId, "hubId must not be null");
    Objects.requireNonNull(windowStart, "windowStart must not be null");
    Objects.requireNonNull(windowEnd, "windowEnd must not be null");
    Objects.requireNonNull(findings, "findings must not be null");
    if (tripId.isBlank()) {
      throw new IllegalArgumentException("tripId must not be blank");
    }
    if (explanation.isBlank()) {
      throw new IllegalArgumentException("explanation must not be blank");
    }
    if (hubId.isPresent() && hubId.orElseThrow().isBlank()) {
      throw new IllegalArgumentException("hubId must not be blank");
    }
    requireWindow(windowStart, windowEnd);
    findings = List.copyOf(findings);
  }

  private static void requireWindow(Optional<Instant> start, Optional<Instant> end) {
    if (start.isPresent() != end.isPresent()) {
      throw new IllegalArgumentException("window bounds must both be present");
    }
    if (start.isPresent() && !start.orElseThrow().isBefore(end.orElseThrow())) {
      throw new IllegalArgumentException("window start must be before window end");
    }
  }
}
