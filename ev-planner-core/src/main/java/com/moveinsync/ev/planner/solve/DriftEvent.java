package com.moveinsync.ev.planner.solve;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * One replanning trigger. {@code subjectId} is an opaque vehicle, charger, or trip id.
 *
 * @param kind what changed
 * @param subjectId opaque id of the affected entity
 * @param observedAt when the trigger was observed, UTC
 * @param reportedSocWh telemetry SoC for an active charging session, watt-hours
 */
public record DriftEvent(
    Kind kind, String subjectId, Instant observedAt, OptionalLong reportedSocWh) {

  /** Why a replan was requested. */
  public enum Kind {
    BATTERY,
    CHARGER_FAILURE,
    TRIP_ADDED,
    TRIP_CANCELLED,
    CAB_REMOVED,
    LATE_CAB
  }

  public DriftEvent(Kind kind, String subjectId, Instant observedAt) {
    this(kind, subjectId, observedAt, OptionalLong.empty());
  }

  public DriftEvent {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(subjectId, "subjectId must not be null");
    Objects.requireNonNull(observedAt, "observedAt must not be null");
    Objects.requireNonNull(reportedSocWh, "reportedSocWh must not be null");
    if (subjectId.isBlank()) {
      throw new IllegalArgumentException("subjectId must not be blank");
    }
    if (subjectId.contains("@") || subjectId.startsWith("+")) {
      throw new IllegalArgumentException("subjectId must be an opaque identifier");
    }
    if (reportedSocWh.isPresent() && reportedSocWh.getAsLong() < 0L) {
      throw new IllegalArgumentException("reportedSocWh must be non-negative");
    }
  }
}
