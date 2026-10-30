package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Validates invariant H1: Vehicle State of Charge (SoC) never drops below the safety floor (15%
 * capacity) at any moment, including during empty deadhead drives.
 */
final class BatteryFloorValidator {

  private final ValidationContext context;

  BatteryFloorValidator(ValidationContext context) {
    this.context = context;
  }

  List<PlanViolation> validate(VehicleSchedule schedule) {
    List<PlanViolation> violations = new ArrayList<>();
    Vehicle vehicle = schedule.vehicle();
    long floorWh = vehicle.calculateFloorSocWh(context.batteryFloorPercentage());

    // Check initial vehicle state
    if (schedule.initialSocWh() < floorWh) {
      violations.add(
          PlanViolation.ofVehicle(
              RuleId.H1_BATTERY_FLOOR,
              vehicle.id(),
              schedule.availableFrom(),
              "Vehicle "
                  + vehicle.id()
                  + " initial SoC ("
                  + schedule.initialSocWh()
                  + " Wh) is below the safety floor ("
                  + floorWh
                  + " Wh)"));
    }

    Location currentLocation = schedule.initialLocation();
    long currentSocWh = schedule.initialSocWh();

    for (RouteActivity activity : schedule.activities()) {
      if (activity instanceof TripAssignment tripAssign) {
        currentSocWh =
            validateTripAssignment(
                tripAssign, vehicle, floorWh, currentLocation, currentSocWh, violations);
        currentLocation = tripAssign.trip().toLocation();
      } else if (activity instanceof ChargingSession session) {
        currentSocWh =
            validateChargingSession(
                session, vehicle, floorWh, currentLocation, currentSocWh, violations);
        Optional<Hub> hubOpt = context.findHub(session.hubId());
        if (hubOpt.isPresent()) {
          currentLocation = hubOpt.get().location();
        }
      }
    }

    return violations;
  }

  private long validateTripAssignment(
      TripAssignment assign,
      Vehicle vehicle,
      long floorWh,
      Location currentLocation,
      long currentSocWh,
      List<PlanViolation> violations) {
    Trip trip = assign.trip();

    // 1. Deadhead to pickup
    long deadheadEnergy =
        calculateDeadheadEnergy(currentLocation, trip.fromLocation(), vehicle, assign);
    long arrivalSocWh = currentSocWh - deadheadEnergy;

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

    // 2. Trip execution
    long tripEnergy =
        context
            .consumptionModel()
            .calculateEnergyWh(vehicle, trip.distanceMeters(), trip.startTs(), context.acOn());
    long dropoffSocWh = arrivalSocWh - tripEnergy;

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

    return assign.departureAfterDropoffSocWh();
  }

  private long validateChargingSession(
      ChargingSession session,
      Vehicle vehicle,
      long floorWh,
      Location currentLocation,
      long currentSocWh,
      List<PlanViolation> violations) {
    Optional<Hub> hubOpt = context.findHub(session.hubId());
    if (hubOpt.isEmpty()) {
      violations.add(
          PlanViolation.ofHub(
              RuleId.H2_CHARGER_CAPACITY,
              session.hubId(),
              session.startTime(),
              "Charging session references unknown hub: " + session.hubId()));
      return session.targetSocWh();
    }

    Location hubLocation = hubOpt.get().location();
    if (!currentLocation.id().equals(hubLocation.id())) {
      int dist =
          context
              .distanceProvider()
              .getDistanceAndDuration(currentLocation, hubLocation)
              .distanceMeters();
      long deadheadEnergy =
          context
              .consumptionModel()
              .calculateEnergyWh(vehicle, dist, session.startTime(), context.acOn());
      long arrivalAtHubSoc = currentSocWh - deadheadEnergy;
      if (arrivalAtHubSoc < floorWh) {
        violations.add(
            PlanViolation.ofVehicle(
                RuleId.H1_BATTERY_FLOOR,
                vehicle.id(),
                session.startTime(),
                "Vehicle "
                    + vehicle.id()
                    + " SoC ("
                    + arrivalAtHubSoc
                    + " Wh) drops below floor ("
                    + floorWh
                    + " Wh) driving to hub "
                    + session.hubId()));
      }
    }

    if (session.startSocWh() < floorWh) {
      violations.add(
          PlanViolation.ofVehicle(
              RuleId.H1_BATTERY_FLOOR,
              vehicle.id(),
              session.startTime(),
              "Vehicle "
                  + vehicle.id()
                  + " starts charging below floor ("
                  + session.startSocWh()
                  + " Wh < "
                  + floorWh
                  + " Wh)"));
    }

    if (session.targetSocWh() > vehicle.batteryCapacityWh()) {
      violations.add(
          PlanViolation.ofVehicle(
              RuleId.H1_BATTERY_FLOOR,
              vehicle.id(),
              session.endTime(),
              "Vehicle "
                  + vehicle.id()
                  + " target SoC ("
                  + session.targetSocWh()
                  + " Wh) exceeds battery capacity ("
                  + vehicle.batteryCapacityWh()
                  + " Wh)"));
    }

    return session.targetSocWh();
  }

  private long calculateDeadheadEnergy(
      Location from, Location to, Vehicle vehicle, TripAssignment assign) {
    if (from.id().equals(to.id())) {
      return 0L;
    }
    int dist = context.distanceProvider().getDistanceAndDuration(from, to).distanceMeters();
    long calculated =
        context
            .consumptionModel()
            .calculateEnergyWh(vehicle, dist, assign.activityStartTime(), context.acOn());
    return Math.max(calculated, assign.deadheadEnergyWh());
  }
}
