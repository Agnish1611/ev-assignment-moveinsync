package com.moveinsync.ev.domain.schedule;

import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable domain representation of a trip assigned to a specific vehicle schedule.
 *
 * <p>Encompasses the deadhead movement from the vehicle's preceding location to the trip pickup
 * point, the trip execution itself, and the battery SoC transitions.
 *
 * @param trip the trip request being fulfilled
 * @param vehicleId the assigned vehicle
 * @param pickupTime actual scheduled pickup time (must match trip.startTs())
 * @param dropoffTime actual scheduled dropoff time (must not exceed trip.latestFinishTs())
 * @param deadheadDistanceMeters empty driving distance to pickup in metres
 * @param deadheadDurationSeconds empty driving duration to pickup in seconds
 * @param deadheadEnergyWh energy consumed during empty drive in Wh
 * @param arrivalAtPickupSocWh battery SoC when arriving at the pickup location
 * @param departureAfterDropoffSocWh battery SoC remaining immediately after passenger dropoff
 */
public record TripAssignment(
    Trip trip,
    VehicleId vehicleId,
    Instant pickupTime,
    Instant dropoffTime,
    int deadheadDistanceMeters,
    int deadheadDurationSeconds,
    long deadheadEnergyWh,
    long arrivalAtPickupSocWh,
    long departureAfterDropoffSocWh)
    implements RouteActivity {

  public TripAssignment {
    Objects.requireNonNull(trip, "trip must not be null");
    Objects.requireNonNull(vehicleId, "vehicleId must not be null");
    Objects.requireNonNull(pickupTime, "pickupTime must not be null");
    Objects.requireNonNull(dropoffTime, "dropoffTime must not be null");

    if (!pickupTime.equals(trip.startTs())) {
      throw new IllegalArgumentException(
          "Pickup time ("
              + pickupTime
              + ") must exactly match trip startTs ("
              + trip.startTs()
              + ")");
    }
    if (dropoffTime.isAfter(trip.latestFinishTs())) {
      throw new IllegalArgumentException(
          "Dropoff time ("
              + dropoffTime
              + ") exceeds trip latestFinishTs ("
              + trip.latestFinishTs()
              + ")");
    }
    if (pickupTime.isAfter(dropoffTime)) {
      throw new IllegalArgumentException(
          "Pickup time (" + pickupTime + ") cannot be after dropoff time (" + dropoffTime + ")");
    }
    if (deadheadDistanceMeters < 0) {
      throw new IllegalArgumentException(
          "deadheadDistanceMeters must be >= 0: " + deadheadDistanceMeters);
    }
    if (deadheadDurationSeconds < 0) {
      throw new IllegalArgumentException(
          "deadheadDurationSeconds must be >= 0: " + deadheadDurationSeconds);
    }
    if (deadheadEnergyWh < 0) {
      throw new IllegalArgumentException("deadheadEnergyWh must be >= 0: " + deadheadEnergyWh);
    }
    if (arrivalAtPickupSocWh < 0) {
      throw new IllegalArgumentException(
          "arrivalAtPickupSocWh must be >= 0: " + arrivalAtPickupSocWh);
    }
    if (departureAfterDropoffSocWh < 0) {
      throw new IllegalArgumentException(
          "departureAfterDropoffSocWh must be >= 0: " + departureAfterDropoffSocWh);
    }
    if (departureAfterDropoffSocWh > arrivalAtPickupSocWh) {
      throw new IllegalArgumentException(
          "departureAfterDropoffSocWh ("
              + departureAfterDropoffSocWh
              + ") cannot exceed arrivalAtPickupSocWh ("
              + arrivalAtPickupSocWh
              + ")");
    }
  }

  @Override
  public Instant activityStartTime() {
    return pickupTime.minusSeconds(deadheadDurationSeconds);
  }

  @Override
  public Instant activityEndTime() {
    return dropoffTime;
  }
}
