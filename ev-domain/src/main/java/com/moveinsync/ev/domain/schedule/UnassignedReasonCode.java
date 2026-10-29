package com.moveinsync.ev.domain.schedule;

/**
 * Standard diagnostic reason codes explaining why a trip could not be scheduled onto the EV fleet
 * and had to be assigned to a backup cab.
 */
public enum UnassignedReasonCode {
  /** The trip is intrinsically infeasible even for a 100% charged cab at nominal conditions. */
  INTRINSICALLY_INFEASIBLE,

  /** No cab in the fleet had sufficient battery charge to reach pickup and complete the trip. */
  NO_CAB_WITH_ENOUGH_CHARGE,

  /** Schedulable if charger capacity at the binding hub was increased by one plug. */
  CHARGER_CONGESTION,

  /**
   * No vehicle was available to reach the pickup location before startTs without violating another
   * trip.
   */
  NO_CAB_FREE_IN_TIME_WINDOW,

  /**
   * Cab could complete the trip but could not maintain the post-trip reserve to reach any charger.
   */
  RESERVE_TO_HUB_VIOLATION,

  /** Trip duration exceeds the allowed latest finish deadline. */
  TIME_WINDOW_VIOLATION,

  /** Assigned to backup as part of a safe patch during solver deadline or failure recovery. */
  SAFE_PATCH_FALLBACK,

  /** Other unspecified operational constraint. */
  OTHER
}
