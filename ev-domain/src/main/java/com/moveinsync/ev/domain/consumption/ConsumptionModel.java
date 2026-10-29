package com.moveinsync.ev.domain.consumption;

import com.moveinsync.ev.domain.vehicle.Vehicle;
import java.time.Instant;

/**
 * Polymorphic interface for vehicle energy consumption calculation.
 *
 * <p>Enforces the domain model: {@code energy = base × traffic(t) × AC}. All arithmetic uses
 * integer operations with conservative rounding UP to the nearest Watt-hour.
 */
public interface ConsumptionModel {

  /** Standard scale factor representing 1.0000x in integer basis points. */
  int FACTOR_SCALE_BASIS_POINTS = 10_000;

  /**
   * Calculates the conservative energy consumption in Watt-hours for a driving segment.
   *
   * @param vehicle vehicle driving the segment
   * @param distanceMeters distance traveled in metres
   * @param travelTime departure or mid-trip time instant in UTC
   * @param acOn whether air conditioning is active during this segment
   * @return total energy consumed in Wh (conservative rounded UP)
   */
  long calculateEnergyWh(Vehicle vehicle, int distanceMeters, Instant travelTime, boolean acOn);
}
