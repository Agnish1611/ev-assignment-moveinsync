package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Validates invariant H1 (battery safety floor) and Decision D2 (strict energy conservation).
 *
 * <p>Energy values claimed in candidate schedules are never trusted. The validator recomputes State
 * of Charge (SoC) independently at each step. Any discrepancy between claimed SoC and ground-truth
 * accumulation produces {@link RuleId#CLAIMED_SOC_MISMATCH}.
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

    checkInitialSocFloor(schedule, vehicle, floorWh, violations);
    validateActivities(schedule, vehicle, floorWh, violations);
    return violations;
  }

  private void checkInitialSocFloor(
      VehicleSchedule schedule, Vehicle vehicle, long floorWh, List<PlanViolation> violations) {
    if (schedule.initialSocWh() < 0 || schedule.initialSocWh() > vehicle.batteryCapacityWh()) {
      violations.add(
          PlanViolation.ofVehicle(
              RuleId.SOC_OUT_OF_RANGE,
              vehicle.id(),
              schedule.availableFrom(),
              "Vehicle "
                  + vehicle.id()
                  + " initial SoC ("
                  + schedule.initialSocWh()
                  + " Wh) is out of bounds [0, "
                  + vehicle.batteryCapacityWh()
                  + " Wh]"));
    }
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
  }

  private void validateActivities(
      VehicleSchedule schedule, Vehicle vehicle, long floorWh, List<PlanViolation> violations) {
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
  }

  private long validateTripAssignment(
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
              RuleId.UNKNOWN_HUB,
              session.hubId(),
              session.startTime(),
              "Charging session references unknown hub: " + session.hubId()));
      return currentSocWh;
    }

    long arrivalAtHubSoc =
        validateDeadheadToHub(
            session, vehicle, floorWh, currentLocation, currentSocWh, hubOpt.get(), violations);

    checkSessionStartSoc(session, vehicle, arrivalAtHubSoc, floorWh, violations);
    checkSessionSocBounds(session, vehicle, violations);
    if (session.startSocWh() > session.targetSocWh()) {
      violations.add(
          PlanViolation.ofVehicle(
              RuleId.H6_CHARGING_CURVE,
              vehicle.id(),
              session.startTime(),
              "Charging session "
                  + session.sessionId()
                  + " start SoC ("
                  + session.startSocWh()
                  + " Wh) exceeds target SoC ("
                  + session.targetSocWh()
                  + " Wh)"));
    }
    return validateSessionDelivery(session, vehicle, arrivalAtHubSoc, violations);
  }

  private void checkSessionStartSoc(
      ChargingSession session,
      Vehicle vehicle,
      long arrivalAtHubSoc,
      long floorWh,
      List<PlanViolation> violations) {
    if (session.startSocWh() != arrivalAtHubSoc) {
      violations.add(
          PlanViolation.ofVehicle(
              RuleId.CLAIMED_SOC_MISMATCH,
              vehicle.id(),
              session.startTime(),
              "Session "
                  + session.sessionId()
                  + " claimed start SoC ("
                  + session.startSocWh()
                  + " Wh) does not match arrival SoC at hub ("
                  + arrivalAtHubSoc
                  + " Wh)"));
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
  }

  private void checkSessionSocBounds(
      ChargingSession session, Vehicle vehicle, List<PlanViolation> violations) {
    if (session.startSocWh() < 0 || session.startSocWh() > vehicle.batteryCapacityWh()) {
      violations.add(
          PlanViolation.ofVehicle(
              RuleId.SOC_OUT_OF_RANGE,
              vehicle.id(),
              session.startTime(),
              "Vehicle "
                  + vehicle.id()
                  + " session start SoC ("
                  + session.startSocWh()
                  + " Wh) is out of bounds [0, "
                  + vehicle.batteryCapacityWh()
                  + " Wh]"));
    }

    if (session.targetSocWh() < 0 || session.targetSocWh() > vehicle.batteryCapacityWh()) {
      violations.add(
          PlanViolation.ofVehicle(
              RuleId.SOC_OUT_OF_RANGE,
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
  }

  private long validateSessionDelivery(
      ChargingSession session,
      Vehicle vehicle,
      long arrivalAtHubSoc,
      List<PlanViolation> violations) {
    Optional<Charger> chargerOpt = context.findCharger(session.chargerId());
    int chargerPowerKw = chargerOpt.map(Charger::powerKw).orElse(vehicle.maxChargeKw());
    int effectivePowerKw = Math.min(chargerPowerKw, vehicle.maxChargeKw());
    long safeStartSoc = Math.max(0L, Math.min(arrivalAtHubSoc, vehicle.batteryCapacityWh()));
    long maxCurveSoc =
        context
            .chargingCurve()
            .calculateTargetSocWh(
                safeStartSoc,
                session.durationSeconds(),
                vehicle.batteryCapacityWh(),
                effectivePowerKw);

    if (session.targetSocWh() > maxCurveSoc) {
      violations.add(
          PlanViolation.ofVehicle(
              RuleId.CLAIMED_SOC_MISMATCH,
              vehicle.id(),
              session.endTime(),
              "Session "
                  + session.sessionId()
                  + " claimed target SoC ("
                  + session.targetSocWh()
                  + " Wh) exceeds curve delivery ("
                  + maxCurveSoc
                  + " Wh)"));
    }

    return Math.min(maxCurveSoc, session.targetSocWh());
  }

  private long validateDeadheadToHub(
      ChargingSession session,
      Vehicle vehicle,
      long floorWh,
      Location currentLocation,
      long currentSocWh,
      Hub hub,
      List<PlanViolation> violations) {
    Location hubLocation = hub.location();
    if (currentLocation.id().equals(hubLocation.id())) {
      return currentSocWh;
    }

    var ddOpt = context.distanceProvider().findDistanceAndDuration(currentLocation, hubLocation);
    if (ddOpt.isEmpty()) {
      violations.add(
          PlanViolation.ofVehicle(
              RuleId.ROUTE_UNAVAILABLE,
              vehicle.id(),
              session.startTime(),
              "No route available from "
                  + currentLocation.id()
                  + " to hub "
                  + hub.id()
                  + " ("
                  + hubLocation.id()
                  + ")"));
      return currentSocWh;
    }

    return checkDeadheadToHubFloor(
        session, vehicle, floorWh, currentSocWh, ddOpt.get().distanceMeters(), violations);
  }

  private long checkDeadheadToHubFloor(
      ChargingSession session,
      Vehicle vehicle,
      long floorWh,
      long currentSocWh,
      int dist,
      List<PlanViolation> violations) {
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

    return arrivalAtHubSoc;
  }

  private long calculateDeadheadEnergy(
      Location from, Location to, Vehicle vehicle, Instant startTime) {
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
                    .calculateEnergyWh(vehicle, dd.distanceMeters(), startTime, context.acOn()))
        .orElse(0L);
  }
}
