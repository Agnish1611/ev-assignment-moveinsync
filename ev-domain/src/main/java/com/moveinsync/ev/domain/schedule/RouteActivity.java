package com.moveinsync.ev.domain.schedule;

import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;

/**
 * Sealed interface representing any timed activity performed by a vehicle along its shift route.
 */
public sealed interface RouteActivity permits ChargingSession, TripAssignment {

  /**
   * The vehicle executing this activity.
   *
   * @return VehicleId
   */
  VehicleId vehicleId();

  /**
   * Activity start time in UTC.
   *
   * @return Instant
   */
  Instant activityStartTime();

  /**
   * Activity end time in UTC.
   *
   * @return Instant
   */
  Instant activityEndTime();
}
