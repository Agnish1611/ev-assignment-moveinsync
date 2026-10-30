package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import java.util.List;
import java.util.Optional;

/** Validates referential integrity between candidate plan entities and ground truth definitions. */
final class PlanReferentialIntegrityValidator {

  private PlanReferentialIntegrityValidator() {}

  static void checkReferentialIntegrity(
      ValidationInput input, FleetPlan candidate, List<PlanViolation> violations) {
    for (VehicleSchedule schedule : candidate.vehicleSchedules()) {
      checkVehicleIntegrity(input, schedule, violations);
      checkInitialStateIntegrity(input, schedule, violations);
      checkActivitiesIntegrity(input, schedule, violations);
    }
  }

  private static void checkVehicleIntegrity(
      ValidationInput input, VehicleSchedule schedule, List<PlanViolation> violations) {
    Optional<Vehicle> gtVehicleOpt = input.findVehicle(schedule.vehicle().id());
    if (gtVehicleOpt.isEmpty()) {
      violations.add(
          PlanViolation.ofVehicle(
              RuleId.UNKNOWN_VEHICLE,
              schedule.vehicle().id(),
              schedule.availableFrom(),
              "Vehicle " + schedule.vehicle().id() + " is not registered in ground truth"));
    } else {
      Vehicle gt = gtVehicleOpt.get();
      if (schedule.vehicle().batteryCapacityWh() != gt.batteryCapacityWh()
          || schedule.vehicle().baseConsumptionWhPerKm() != gt.baseConsumptionWhPerKm()
          || schedule.vehicle().maxChargeKw() != gt.maxChargeKw()) {
        violations.add(
            PlanViolation.ofVehicle(
                RuleId.CLAIMED_SOC_MISMATCH,
                schedule.vehicle().id(),
                schedule.availableFrom(),
                "Vehicle "
                    + schedule.vehicle().id()
                    + " claimed specifications do not match ground truth"));
      }
    }
  }

  private static void checkInitialStateIntegrity(
      ValidationInput input, VehicleSchedule schedule, List<PlanViolation> violations) {
    Long gtInitialSoc = input.initialSocsWh().get(schedule.vehicle().id());
    if (gtInitialSoc != null && schedule.initialSocWh() != gtInitialSoc) {
      violations.add(
          PlanViolation.ofVehicle(
              RuleId.CLAIMED_SOC_MISMATCH,
              schedule.vehicle().id(),
              schedule.availableFrom(),
              "Vehicle "
                  + schedule.vehicle().id()
                  + " claimed initial SoC ("
                  + schedule.initialSocWh()
                  + " Wh) does not match ground truth ("
                  + gtInitialSoc
                  + " Wh)"));
    }

    Location gtInitialLoc = input.initialLocations().get(schedule.vehicle().id());
    if (gtInitialLoc != null && !schedule.initialLocation().id().equals(gtInitialLoc.id())) {
      violations.add(
          PlanViolation.ofVehicle(
              RuleId.ROUTE_UNAVAILABLE,
              schedule.vehicle().id(),
              schedule.availableFrom(),
              "Vehicle "
                  + schedule.vehicle().id()
                  + " claimed initial location ("
                  + schedule.initialLocation().id()
                  + ") does not match ground truth ("
                  + gtInitialLoc.id()
                  + ")"));
    }
  }

  private static void checkActivitiesIntegrity(
      ValidationInput input, VehicleSchedule schedule, List<PlanViolation> violations) {
    for (RouteActivity activity : schedule.activities()) {
      if (activity.activityStartTime().isBefore(input.horizonStart())
          || activity.activityEndTime().isAfter(input.horizonEnd())) {
        violations.add(
            PlanViolation.ofVehicle(
                RuleId.ACTIVITY_OUTSIDE_HORIZON,
                schedule.vehicle().id(),
                activity.activityStartTime(),
                "Activity on vehicle "
                    + schedule.vehicle().id()
                    + " ["
                    + activity.activityStartTime()
                    + ", "
                    + activity.activityEndTime()
                    + "] falls outside plan horizon ["
                    + input.horizonStart()
                    + ", "
                    + input.horizonEnd()
                    + "]"));
      }
      if (activity instanceof TripAssignment assign) {
        checkTripAssignmentIntegrity(input, schedule, assign, violations);
      } else if (activity instanceof ChargingSession session) {
        checkSessionIntegrity(input, session, violations);
      }
    }
  }

  private static void checkTripAssignmentIntegrity(
      ValidationInput input,
      VehicleSchedule schedule,
      TripAssignment assign,
      List<PlanViolation> violations) {
    Optional<Trip> gtTripOpt = input.findTrip(assign.trip().id());
    if (gtTripOpt.isEmpty()) {
      violations.add(
          PlanViolation.ofTrip(
              RuleId.UNKNOWN_TRIP,
              assign.trip().id(),
              schedule.vehicle().id(),
              assign.pickupTime(),
              "Trip " + assign.trip().id() + " is not registered in ground truth"));
    } else {
      Trip gt = gtTripOpt.get();
      if (assign.trip().distanceMeters() != gt.distanceMeters()
          || assign.trip().estimatedDurationSeconds() != gt.estimatedDurationSeconds()
          || !assign.trip().startTs().equals(gt.startTs())
          || !assign.trip().latestFinishTs().equals(gt.latestFinishTs())
          || !assign.trip().fromLocation().id().equals(gt.fromLocation().id())
          || !assign.trip().toLocation().id().equals(gt.toLocation().id())) {
        violations.add(
            PlanViolation.ofTrip(
                RuleId.H3_TRIP_TIMING,
                assign.trip().id(),
                schedule.vehicle().id(),
                assign.pickupTime(),
                "Trip " + assign.trip().id() + " claimed attributes do not match ground truth"));
      }
    }
  }

  private static void checkSessionIntegrity(
      ValidationInput input, ChargingSession session, List<PlanViolation> violations) {
    if (input.findHub(session.hubId()).isEmpty()) {
      violations.add(
          PlanViolation.ofHub(
              RuleId.UNKNOWN_HUB,
              session.hubId(),
              session.startTime(),
              "Hub " + session.hubId() + " is not registered in ground truth"));
    }
    if (input.findCharger(session.chargerId()).isEmpty()) {
      violations.add(
          PlanViolation.ofHub(
              RuleId.UNKNOWN_CHARGER,
              session.hubId(),
              session.startTime(),
              "Charger " + session.chargerId() + " is not registered in ground truth"));
    }
  }
}
