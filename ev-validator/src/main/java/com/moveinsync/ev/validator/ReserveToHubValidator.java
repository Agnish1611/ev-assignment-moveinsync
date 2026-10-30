package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates invariant H5: After every trip dropoff, the vehicle must retain sufficient battery
 * charge to reach at least one operational charging hub while staying strictly above the safety
 * floor (reserve-to-hub invariant).
 */
final class ReserveToHubValidator {

  private final ValidationContext context;

  ReserveToHubValidator(ValidationContext context) {
    this.context = context;
  }

  List<PlanViolation> validate(FleetPlan plan) {
    if (!context.enforceReserveToHub() || context.hubs().isEmpty()) {
      return List.of();
    }

    List<PlanViolation> violations = new ArrayList<>();
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      validateSchedule(schedule, violations);
    }
    return violations;
  }

  private void validateSchedule(VehicleSchedule schedule, List<PlanViolation> violations) {
    Vehicle vehicle = schedule.vehicle();
    long floorWh = vehicle.calculateFloorSocWh(context.batteryFloorPercentage());

    for (RouteActivity activity : schedule.activities()) {
      if (activity instanceof TripAssignment assign) {
        validateTripReserve(assign, vehicle, floorWh, violations);
      }
    }
  }

  private void validateTripReserve(
      TripAssignment assign, Vehicle vehicle, long floorWh, List<PlanViolation> violations) {
    Trip trip = assign.trip();
    Location dropoffLocation = trip.toLocation();
    long dropoffSocWh = assign.departureAfterDropoffSocWh();

    boolean canReachAtLeastOneHub = false;

    for (Hub hub : context.hubs().values()) {
      var ddOpt =
          context.distanceProvider().findDistanceAndDuration(dropoffLocation, hub.location());
      if (ddOpt.isEmpty()) {
        continue;
      }
      int distMeters = ddOpt.get().distanceMeters();
      long energyToHub =
          context
              .consumptionModel()
              .calculateEnergyWh(vehicle, distMeters, assign.dropoffTime(), context.acOn());

      long arrivalSocAtHub = dropoffSocWh - energyToHub;
      if (arrivalSocAtHub >= floorWh) {
        canReachAtLeastOneHub = true;
        break;
      }
    }

    if (!canReachAtLeastOneHub) {
      violations.add(
          PlanViolation.ofTrip(
              RuleId.H5_RESERVE_TO_HUB,
              trip.id(),
              assign.vehicleId(),
              assign.dropoffTime(),
              "Vehicle "
                  + assign.vehicleId()
                  + " after trip "
                  + trip.id()
                  + " dropoff at "
                  + dropoffLocation.id()
                  + " (remaining SoC: "
                  + dropoffSocWh
                  + " Wh) cannot reach any operational hub above the safety floor ("
                  + floorWh
                  + " Wh)"));
    }
  }
}
