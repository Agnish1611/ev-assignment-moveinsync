package com.moveinsync.ev.planner.construct;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * What happened when one cab was offered an unassigned trip.
 *
 * @param cabId opaque cab id
 * @param constraint the check that stopped the insertion
 * @param arrivalSocWh SoC at the pickup after the empty drive, when that drive fits in the pack
 * @param floorWh planning floor for this cab, in watt-hours
 */
public record CabFinding(
    String cabId, BindingConstraint constraint, OptionalLong arrivalSocWh, long floorWh) {

  public CabFinding {
    Objects.requireNonNull(cabId, "cabId must not be null");
    Objects.requireNonNull(constraint, "constraint must not be null");
    Objects.requireNonNull(arrivalSocWh, "arrivalSocWh must not be null");
    if (cabId.isBlank() || cabId.contains("@") || cabId.startsWith("+")) {
      throw new IllegalArgumentException("cabId must be an opaque identifier");
    }
    if (arrivalSocWh.isPresent() && arrivalSocWh.getAsLong() < 0L) {
      throw new IllegalArgumentException("arrivalSocWh must be non-negative");
    }
    if (floorWh < 0L) {
      throw new IllegalArgumentException("floorWh must be non-negative: " + floorWh);
    }
  }
}
