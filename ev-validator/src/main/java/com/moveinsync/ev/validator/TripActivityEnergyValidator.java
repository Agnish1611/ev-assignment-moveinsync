package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import java.time.Instant;
import java.util.List;

/** Validates energy consumption and SoC floor/claims for trip assignments and deadhead travel. */
final class TripActivityEnergyValidator {

  private final ValidationContext context;

  TripActivityEnergyValidator(ValidationContext context) {
    this.context = context;
  }

  long validateTripAssignment(
      TripAssignment assign,
      Vehicle vehicle,
      long floorWh,
      Location currentLocation,
      long currentSocWh,
      List<PlanViolation> violations) {
    Trip trip = assign.trip();

    long arrivalSocWh =
        validateDeadheadToPickup(
            assign, vehicle, floorWh, currentLocation, currentSocWh, violations);

    long tripEnergy =
        context
            .consumptionModel()
            .calculateEnergyWh(vehicle, trip.distanceMeters(), trip.startTs(), context.acOn());
    long dropoffSocWh = arrivalSocWh - tripEnergy;

    checkDropoffSocFloorAndClaims(assign, vehicle, trip, dropoffSocWh, floorWh, violations);
    return dropoffSocWh;
  }

  private void checkDropoffSocFloorAndClaims(
      TripAssignment assign,
      Vehicle vehicle,
      Trip trip,
      long dropoffSocWh,
      long floorWh,
      List<PlanViolation> violations) {
    if (dropoffSocWh < floorWh) {
      violations.add(
          PlanViolation.ofTrip(
              RuleId.H1_BATTERY_FLOOR,
              trip.id(),
              vehicle.id(),
              assign.dropoffTime(),
              "Vehicle "
                  + vehicle.id()
                  + " SoC ("
                  + dropoffSocWh
                  + " Wh) drops below floor ("
                  + floorWh
                  + " Wh) upon dropoff for trip "
                  + trip.id()));
    }

    if (assign.departureAfterDropoffSocWh() < 0
        || assign.departureAfterDropoffSocWh() > vehicle.batteryCapacityWh()) {
      violations.add(
          PlanViolation.ofTrip(
              RuleId.SOC_OUT_OF_RANGE,
              trip.id(),
              vehicle.id(),
              assign.dropoffTime(),
              "Vehicle "
                  + vehicle.id()
                  + " claimed departure SoC after dropoff ("
                  + assign.departureAfterDropoffSocWh()
                  + " Wh) is out of bounds [0, "
                  + vehicle.batteryCapacityWh()
                  + " Wh]"));
    }

    if (assign.departureAfterDropoffSocWh() != dropoffSocWh) {
      violations.add(
          PlanViolation.ofTrip(
              RuleId.CLAIMED_SOC_MISMATCH,
              trip.id(),
              vehicle.id(),
              assign.dropoffTime(),
              "Claimed departure SoC ("
                  + assign.departureAfterDropoffSocWh()
                  + " Wh) does not match recomputed dropoff SoC ("
                  + dropoffSocWh
                  + " Wh) for trip "
                  + trip.id()));
    }
  }

  private long validateDeadheadToPickup(
      TripAssignment assign,
      Vehicle vehicle,
      long floorWh,
      Location currentLocation,
      long currentSocWh,
      List<PlanViolation> violations) {
    Trip trip = assign.trip();
    checkDeadheadRoute(currentLocation, trip, vehicle, assign.pickupTime(), violations);

    long deadheadEnergy =
        calculateDeadheadEnergy(currentLocation, trip.fromLocation(), vehicle, assign.pickupTime());
    long arrivalSocWh = currentSocWh - deadheadEnergy;

    checkDeadheadClaimsAndFloor(
        assign, vehicle, trip, deadheadEnergy, arrivalSocWh, floorWh, violations);
    return arrivalSocWh;
  }

  private void checkDeadheadRoute(
      Location currentLocation,
      Trip trip,
      Vehicle vehicle,
      Instant pickupTime,
      List<PlanViolation> violations) {
    if (!currentLocation.id().equals(trip.fromLocation().id())
        && context
            .distanceProvider()
            .findDistanceAndDuration(currentLocation, trip.fromLocation())
            .isEmpty()) {
      violations.add(
          PlanViolation.ofTrip(
              RuleId.ROUTE_UNAVAILABLE,
              trip.id(),
              vehicle.id(),
              pickupTime,
              "No route available from "
                  + currentLocation.id()
                  + " to "
                  + trip.fromLocation().id()));
    }
  }

  private void checkDeadheadClaimsAndFloor(
      TripAssignment assign,
      Vehicle vehicle,
      Trip trip,
      long deadheadEnergy,
      long arrivalSocWh,
      long floorWh,
      List<PlanViolation> violations) {
    if (assign.deadheadEnergyWh() != deadheadEnergy) {
      violations.add(
          PlanViolation.ofTrip(
              RuleId.CLAIMED_SOC_MISMATCH,
              trip.id(),
              vehicle.id(),
              assign.pickupTime(),
              "Claimed deadhead energy ("
                  + assign.deadheadEnergyWh()
                  + " Wh) does not match recomputed deadhead energy ("
                  + deadheadEnergy
                  + " Wh)"));
    }

    if (assign.arrivalAtPickupSocWh() != arrivalSocWh) {
      violations.add(
          PlanViolation.ofTrip(
              RuleId.CLAIMED_SOC_MISMATCH,
              trip.id(),
              vehicle.id(),
              assign.pickupTime(),
              "Claimed arrival SoC ("
                  + assign.arrivalAtPickupSocWh()
                  + " Wh) does not match recomputed arrival SoC ("
                  + arrivalSocWh
                  + " Wh)"));
    }

    if (arrivalSocWh < floorWh) {
      violations.add(
          PlanViolation.ofTrip(
              RuleId.H1_BATTERY_FLOOR,
              trip.id(),
              vehicle.id(),
              trip.startTs(),
              "Vehicle "
                  + vehicle.id()
                  + " SoC ("
                  + arrivalSocWh
                  + " Wh) drops below floor ("
                  + floorWh
                  + " Wh) upon deadhead arrival at pickup for trip "
                  + trip.id()));
    }
  }

  long calculateDeadheadEnergy(Location from, Location to, Vehicle vehicle, Instant time) {
    if (from.id().equals(to.id())) {
      return 0L;
    }
    return context
        .distanceProvider()
        .findDistanceAndDuration(from, to)
        .map(
            dd ->
                context
                    .consumptionModel()
                    .calculateEnergyWh(vehicle, dd.distanceMeters(), time, context.acOn()))
        .orElse(0L);
  }
}
