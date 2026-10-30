package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Validates invariant H6: Charging session durations strictly match or exceed the deterministic
 * two-segment curve (fast segment up to 80% SoC, slow segment above 80% SoC).
 */
final class ChargingCurveValidator {

  private final ValidationContext context;

  ChargingCurveValidator(ValidationContext context) {
    this.context = context;
  }

  List<PlanViolation> validate(FleetPlan plan) {
    List<PlanViolation> violations = new ArrayList<>();

    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      Vehicle vehicle = schedule.vehicle();
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof ChargingSession session) {
          validateSessionDuration(session, vehicle, violations);
        }
      }
    }

    return violations;
  }

  private void validateSessionDuration(
      ChargingSession session, Vehicle vehicle, List<PlanViolation> violations) {
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
      return;
    }

    Optional<Charger> chargerOpt = context.findCharger(session.chargerId());
    int chargerPowerKw = chargerOpt.map(Charger::powerKw).orElse(vehicle.maxChargeKw());
    int effectivePowerKw = Math.min(chargerPowerKw, vehicle.maxChargeKw());

    if (session.startSocWh() < 0 || session.startSocWh() > vehicle.batteryCapacityWh()) {
      violations.add(
          PlanViolation.ofVehicle(
              RuleId.SOC_OUT_OF_RANGE,
              vehicle.id(),
              session.startTime(),
              "Charging session "
                  + session.sessionId()
                  + " start SoC ("
                  + session.startSocWh()
                  + " Wh) is out of bounds [0, "
                  + vehicle.batteryCapacityWh()
                  + " Wh]"));
      return;
    }

    if (session.targetSocWh() < 0 || session.targetSocWh() > vehicle.batteryCapacityWh()) {
      violations.add(
          PlanViolation.ofVehicle(
              RuleId.SOC_OUT_OF_RANGE,
              vehicle.id(),
              session.endTime(),
              "Charging session "
                  + session.sessionId()
                  + " target SoC ("
                  + session.targetSocWh()
                  + " Wh) is out of bounds [0, "
                  + vehicle.batteryCapacityWh()
                  + " Wh]"));
      return;
    }

    if (session.startSocWh() > session.targetSocWh()) {
      violations.add(
          PlanViolation.ofVehicle(
              RuleId.H6_CHARGING_CURVE,
              vehicle.id(),
              session.startTime(),
              "Charging session "
                  + session.sessionId()
                  + " has startSocWh ("
                  + session.startSocWh()
                  + ") greater than targetSocWh ("
                  + session.targetSocWh()
                  + ")"));
      return;
    }

    long requiredDurationSeconds =
        context
            .chargingCurve()
            .calculateDurationSeconds(
                session.startSocWh(),
                session.targetSocWh(),
                vehicle.batteryCapacityWh(),
                effectivePowerKw);

    long actualDurationSeconds = session.durationSeconds();

    if (actualDurationSeconds < requiredDurationSeconds) {
      violations.add(
          PlanViolation.ofVehicle(
              RuleId.H6_CHARGING_CURVE,
              vehicle.id(),
              session.startTime(),
              "Charging session "
                  + session.sessionId()
                  + " on vehicle "
                  + vehicle.id()
                  + " duration ("
                  + actualDurationSeconds
                  + "s) is shorter than required ("
                  + requiredDurationSeconds
                  + "s) by the two-segment charging curve to reach "
                  + session.targetSocWh()
                  + " Wh at "
                  + effectivePowerKw
                  + " kW"));
    }
  }
}
