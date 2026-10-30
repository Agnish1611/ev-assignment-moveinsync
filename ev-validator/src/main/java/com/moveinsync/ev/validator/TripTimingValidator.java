package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates invariant H3: Every assigned trip starts exactly on time and finishes by its latest
 * allowed finish.
 */
final class TripTimingValidator {

  List<PlanViolation> validate(FleetPlan plan) {
    List<PlanViolation> violations = new ArrayList<>();

    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof TripAssignment assign) {
          validateAssignmentTiming(assign, violations);
        }
      }
    }

    return violations;
  }

  private void validateAssignmentTiming(TripAssignment assign, List<PlanViolation> violations) {
    validateExactPickup(assign, violations);
    validateFinishDeadline(assign, violations);
    validatePhysicalDuration(assign, violations);
  }

  private void validateExactPickup(TripAssignment assign, List<PlanViolation> violations) {
    Trip trip = assign.trip();
    if (!assign.pickupTime().equals(trip.startTs())) {
      violations.add(
          PlanViolation.ofTrip(
              RuleId.H3_TRIP_TIMING,
              trip.id(),
              assign.vehicleId(),
              assign.pickupTime(),
              "Trip "
                  + trip.id()
                  + " scheduled pickup time ("
                  + assign.pickupTime()
                  + ") does not match required startTs ("
                  + trip.startTs()
                  + ")"));
    }
  }

  private void validateFinishDeadline(TripAssignment assign, List<PlanViolation> violations) {
    Trip trip = assign.trip();
    if (assign.dropoffTime().isAfter(trip.latestFinishTs())) {
      violations.add(
          PlanViolation.ofTrip(
              RuleId.H3_TRIP_TIMING,
              trip.id(),
              assign.vehicleId(),
              assign.dropoffTime(),
              "Trip "
                  + trip.id()
                  + " scheduled dropoff time ("
                  + assign.dropoffTime()
                  + ") exceeds latest allowed finish deadline ("
                  + trip.latestFinishTs()
                  + ")"));
    }
  }

  private void validatePhysicalDuration(TripAssignment assign, List<PlanViolation> violations) {
    Trip trip = assign.trip();
    Instant minPhysicallyPossibleDropoff =
        assign.pickupTime().plusSeconds(trip.estimatedDurationSeconds());
    if (assign.dropoffTime().isBefore(minPhysicallyPossibleDropoff)) {
      violations.add(
          PlanViolation.ofTrip(
              RuleId.H3_TRIP_TIMING,
              trip.id(),
              assign.vehicleId(),
              assign.dropoffTime(),
              "Trip "
                  + trip.id()
                  + " dropoff ("
                  + assign.dropoffTime()
                  + ") is earlier than minimum physical travel time allows ("
                  + minPhysicallyPossibleDropoff
                  + ")"));
    }
  }
}
