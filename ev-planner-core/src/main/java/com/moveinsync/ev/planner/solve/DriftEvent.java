package com.moveinsync.ev.planner.solve;

import java.time.Instant;
import java.util.Objects;

/**
 * One replanning trigger. {@code subjectId} is an opaque vehicle, charger, or trip id.
 *
 * @param kind what changed
 * @param subjectId opaque id of the affected entity
 * @param observedAt when the trigger was observed, UTC
 */
public record DriftEvent(Kind kind, String subjectId, Instant observedAt) {

  /** Why a replan was requested. */
  public enum Kind {
    BATTERY,
    CHARGER_FAILURE,
    TRIP_ADDED,
    TRIP_CANCELLED,
    CAB_REMOVED,
    LATE_CAB
  }

  public DriftEvent {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(subjectId, "subjectId must not be null");
    Objects.requireNonNull(observedAt, "observedAt must not be null");
    if (subjectId.isBlank()) {
      throw new IllegalArgumentException("subjectId must not be blank");
    }
    if (subjectId.contains("@") || subjectId.startsWith("+")) {
      throw new IllegalArgumentException("subjectId must be an opaque identifier");
    }
  }
}
