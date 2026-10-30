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

    long arrivalSocWh =
        validateDeadheadToPickup(
            assign, vehicle, floorWh, currentLocation, currentSocWh, violations);

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

    return dropoffSocWh;
  }

  private long validateDeadheadToPickup(
      TripAssignment assign,
      Vehicle vehicle,
      long floorWh,
      Location currentLocation,
      long currentSocWh,
      List<PlanViolation> violations) {
    Trip trip = assign.trip();
    long deadheadEnergy =
        calculateDeadheadEnergy(currentLocation, trip.fromLocation(), vehicle, assign.pickupTime());
    long arrivalSocWh = currentSocWh - deadheadEnergy;

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

    return arrivalSocWh;
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

    Optional<Charger> chargerOpt = context.findCharger(session.chargerId());
    int chargerPowerKw = chargerOpt.map(Charger::powerKw).orElse(vehicle.maxChargeKw());
    int effectivePowerKw = Math.min(chargerPowerKw, vehicle.maxChargeKw());
    long maxCurveSoc =
        context
            .chargingCurve()
            .calculateTargetSocWh(
                arrivalAtHubSoc,
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

    return arrivalAtHubSoc;
  }

  private long calculateDeadheadEnergy(
      Location from, Location to, Vehicle vehicle, Instant startTime) {
    if (from.id().equals(to.id())) {
      return 0L;
    }
    int dist = context.distanceProvider().getDistanceAndDuration(from, to).distanceMeters();
    return context.consumptionModel().calculateEnergyWh(vehicle, dist, startTime, context.acOn());
  }
}
