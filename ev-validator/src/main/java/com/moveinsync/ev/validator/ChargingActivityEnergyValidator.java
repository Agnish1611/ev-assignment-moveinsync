package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import java.util.List;
import java.util.Optional;

/** Validates energy dynamics, curve deliveries, and SoC bounds for charging sessions at hubs. */
final class ChargingActivityEnergyValidator {

  private final ValidationContext context;
  private final TripActivityEnergyValidator tripEnergyValidator;

  ChargingActivityEnergyValidator(
      ValidationContext context, TripActivityEnergyValidator tripEnergyValidator) {
    this.context = context;
    this.tripEnergyValidator = tripEnergyValidator;
  }

  long validateChargingSession(
      ChargingSession session,
      Vehicle vehicle,
      long floorWh,
      Location currentLocation,
      long currentSocWh,
      List<PlanViolation> violations) {
    if (!session.startTime().isBefore(session.endTime())) {
      violations.add(
          PlanViolation.ofVehicle(
              RuleId.H6_CHARGING_CURVE,
              vehicle.id(),
              session.startTime(),
              "Charging session "
                  + session.sessionId()
                  + " startTime ("
                  + session.startTime()
                  + ") must be strictly before endTime ("
                  + session.endTime()
                  + ")"));
      return currentSocWh;
    }

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

    if (session.targetSocWh() != maxCurveSoc) {
      violations.add(
          PlanViolation.ofVehicle(
              RuleId.CLAIMED_SOC_MISMATCH,
              vehicle.id(),
              session.endTime(),
              "Session "
                  + session.sessionId()
                  + " claimed target SoC ("
                  + session.targetSocWh()
                  + " Wh) does not match curve delivery ("
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
    if (!currentLocation.id().equals(hubLocation.id())
        && context
            .distanceProvider()
            .findDistanceAndDuration(currentLocation, hubLocation)
            .isEmpty()) {
      violations.add(
          PlanViolation.ofHub(
              RuleId.ROUTE_UNAVAILABLE,
              session.hubId(),
              session.startTime(),
              "No route available from "
                  + currentLocation.id()
                  + " to hub location "
                  + hubLocation.id()));
    }

    long deadheadToHub =
        tripEnergyValidator.calculateDeadheadEnergy(
            currentLocation, hubLocation, vehicle, session.startTime());
    long arrivalSocWh = currentSocWh - deadheadToHub;

    if (arrivalSocWh < floorWh) {
      violations.add(
          PlanViolation.ofHub(
              RuleId.H1_BATTERY_FLOOR,
              session.hubId(),
              session.startTime(),
              "Vehicle "
                  + vehicle.id()
                  + " SoC ("
                  + arrivalSocWh
                  + " Wh) drops below floor ("
                  + floorWh
                  + " Wh) on arrival at hub "
                  + session.hubId()));
    }
    return arrivalSocWh;
  }
}
