package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.vehicle.Vehicle;
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
  private final TripActivityEnergyValidator tripEnergyValidator;
  private final ChargingActivityEnergyValidator chargingEnergyValidator;

  BatteryFloorValidator(ValidationContext context) {
    this.context = context;
    this.tripEnergyValidator = new TripActivityEnergyValidator(context);
    this.chargingEnergyValidator =
        new ChargingActivityEnergyValidator(context, tripEnergyValidator);
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
            tripEnergyValidator.validateTripAssignment(
                tripAssign, vehicle, floorWh, currentLocation, currentSocWh, violations);
        currentLocation = tripAssign.trip().toLocation();
      } else if (activity instanceof ChargingSession session) {
        currentSocWh =
            chargingEnergyValidator.validateChargingSession(
                session, vehicle, floorWh, currentLocation, currentSocWh, violations);
        Optional<Hub> hubOpt = context.findHub(session.hubId());
        if (hubOpt.isPresent()) {
          currentLocation = hubOpt.get().location();
        }
      }
    }
  }
}
