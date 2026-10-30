package com.moveinsync.ev.validator;

/**
 * Enumeration of hard invariant rules enforced by the independent plan validator.
 *
 * <p>These correspond to invariants H1 through H6 defined in the architectural specification.
 */
public enum RuleId {
  /** H1: Battery State of Charge (SoC) never falls below the safety floor (15%) at any point. */
  H1_BATTERY_FLOOR,

  /**
   * H2: Concurrent charging sessions at a hub never exceed physical charger capacity at any second.
   */
  H2_CHARGER_CAPACITY,

  /** H3: Every assigned trip starts exactly on time and finishes by its latest allowed finish. */
  H3_TRIP_TIMING,

  /** H4: A cab never performs overlapping activities (single cab no-overlap). */
  H4_ASSIGNMENT_OVERLAP,

  /** H4: Each trip is assigned to at most one cab and appears at most once across the plan. */
  H4_DOUBLE_ASSIGNMENT,

  /**
   * H5: Reserve-to-hub invariant: after every trip the cab can reach at least one charger above the
   * floor.
   */
  H5_RESERVE_TO_HUB,

  /**
   * H6: Charging session duration strictly matches or exceeds the deterministic two-segment curve.
   */
  H6_CHARGING_CURVE
}
