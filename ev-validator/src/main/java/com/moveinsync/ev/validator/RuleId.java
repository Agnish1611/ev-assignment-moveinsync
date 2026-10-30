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
  H6_CHARGING_CURVE,

  /** Multi-tenant isolation breach (cross-tenant resource reference). */
  TENANT_MISMATCH,

  /** Candidate schedule references an unknown vehicle identifier not in ground truth. */
  UNKNOWN_VEHICLE,

  /** Candidate schedule references an unknown trip identifier not in ground truth. */
  UNKNOWN_TRIP,

  /** Candidate session references an unknown hub identifier not in ground truth. */
  UNKNOWN_HUB,

  /** Candidate session references an unknown charger identifier not in ground truth. */
  UNKNOWN_CHARGER,

  /** Candidate claims an SoC that differs from independently recomputed ground truth energy. */
  CLAIMED_SOC_MISMATCH,

  /** Candidate schedules a session on a faulty charger or during a scheduled outage window. */
  CHARGER_UNAVAILABLE,

  /** Candidate books two concurrent sessions on the exact same charger plug. */
  CHARGER_DOUBLE_BOOKED,

  /**
   * A required trip from the input pool is missing from both assigned and unassigned collections.
   */
  TRIP_OMITTED,

  /** Candidate unassigned list references an unknown trip. */
  TRIP_UNKNOWN,

  /** A trip appears simultaneously in both assigned schedules and unassigned list. */
  TRIP_ASSIGNED_AND_UNASSIGNED,

  /** Charging session claimed cost differs from independent tariff calculation. */
  COST_MISMATCH,

  /** State of charge value is physically out of bounds (negative or exceeding battery capacity). */
  SOC_OUT_OF_RANGE,

  /** Routing distance/duration unavailable for an assigned geographic corridor. */
  ROUTE_UNAVAILABLE,

  /** An assigned trip or charging session is scheduled outside the plan horizon (Decision D10). */
  ACTIVITY_OUTSIDE_HORIZON,

  /** Unexpected exception trapped by the validator top-level totality guard (fail-closed). */
  VALIDATOR_INTERNAL_ERROR
}
