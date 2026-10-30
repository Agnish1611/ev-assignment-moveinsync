package com.moveinsync.ev.sim;

/**
 * One named shift the scenario generator can build. The same profile and seed rebuild the same
 * fleet.
 */
public enum ShiftProfile {
  /** The case-study evening shift: 300 cabs, 40 chargers, 1,500 trips. The seed is not applied. */
  EVENING_SCALE,

  /** One cab starts near the floor and one trip needs more energy than that start can cover. */
  TIGHT_BATTERY,

  /** Several cabs share a hub that has a single plug. */
  CHARGER_SCARCE,

  /** More trips share one pickup instant than there are cabs. */
  PEAK_HEAVY,

  /**
   * The shift crosses the America/New_York fallback on 2026-11-01, so one local hour occurs twice.
   */
  DST_CROSSING
}
