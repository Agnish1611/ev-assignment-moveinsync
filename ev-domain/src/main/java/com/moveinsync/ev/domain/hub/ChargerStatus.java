package com.moveinsync.ev.domain.hub;

/** Operational status of an EV charger. */
public enum ChargerStatus {
  /** Fully operational and available for booking or charging. */
  OPERATIONAL,

  /** Out of service due to an unexpected hardware, network, or electrical fault. */
  FAULTY,

  /** Temporarily offline for scheduled maintenance. */
  MAINTENANCE;

  /**
   * Checks whether the charger can currently accept a vehicle for charging.
   *
   * @return true if operational, false otherwise
   */
  public boolean isAvailable() {
    return this == OPERATIONAL;
  }
}
