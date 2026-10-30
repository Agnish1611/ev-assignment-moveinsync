package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates invariant H4: Each trip is assigned to at most one cab, and a cab never does two things
 * at once (single cab no-overlap with feasible transit).
 */
final class AssignmentValidator {

  private final ValidationContext context;

  AssignmentValidator(ValidationContext context) {
    this.context = context;
  }

  List<PlanViolation> validate(FleetPlan plan) {
    List<PlanViolation> violations = new ArrayList<>();
    validateSingleAssignment(plan, violations);
    validateNoOverlap(plan, violations);
    return violations;
  }

  private void validateSingleAssignment(FleetPlan plan, List<PlanViolation> violations) {
    Map<TripId, List<VehicleId>> assignedTrips = new HashMap<>();
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof TripAssignment assign) {
          assignedTrips
              .computeIfAbsent(assign.trip().id(), k -> new ArrayList<>())
              .add(schedule.vehicleId());
        }
      }
    }

    // Check for duplicate assignments in fleet schedules
    for (Map.Entry<TripId, List<VehicleId>> entry : assignedTrips.entrySet()) {
      if (entry.getValue().size() > 1) {
        violations.add(
            PlanViolation.ofTrip(
                RuleId.H4_DOUBLE_ASSIGNMENT,
                entry.getKey(),
                entry.getValue().get(0),
                null,
                "Trip "
                    + entry.getKey()
                    + " is assigned multiple times to vehicles: "
                    + entry.getValue()));
      }
    }
  }

  private void validateNoOverlap(FleetPlan plan, List<PlanViolation> violations) {
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      List<RouteActivity> activities = new ArrayList<>(schedule.activities());
      activities.sort(Comparator.comparing(RouteActivity::activityStartTime));

      for (int i = 0; i < activities.size() - 1; i++) {
        RouteActivity curr = activities.get(i);
        RouteActivity next = activities.get(i + 1);

        // Check 1: Temporal overlap
        if (curr.activityEndTime().isAfter(next.activityStartTime())) {
          violations.add(
              PlanViolation.ofVehicle(
                  RuleId.H4_ASSIGNMENT_OVERLAP,
                  schedule.vehicleId(),
                  next.activityStartTime(),
                  "Vehicle "
                      + schedule.vehicleId()
                      + " has overlapping activities: activity ending at "
                      + curr.activityEndTime()
                      + " overlaps activity starting at "
                      + next.activityStartTime()));
        }

        // Check 2: Physical transit feasibility between consecutive activities
        validateTransitGap(curr, next, schedule.vehicleId(), violations);
      }
    }
  }

  private void validateTransitGap(
      RouteActivity curr, RouteActivity next, VehicleId vehicleId, List<PlanViolation> violations) {
    Location endLoc = resolveEndLocation(curr);
    Location startLoc = resolveStartLocation(next);

    if (endLoc != null && startLoc != null && !endLoc.id().equals(startLoc.id())) {
      var ddOpt = context.distanceProvider().findDistanceAndDuration(endLoc, startLoc);
      if (ddOpt.isEmpty()) {
        violations.add(
            PlanViolation.ofVehicle(
                RuleId.ROUTE_UNAVAILABLE,
                vehicleId,
                next.activityStartTime(),
                "No transit corridor route available between "
                    + endLoc.id()
                    + " and "
                    + startLoc.id()));
        return;
      }
      int transitDuration = ddOpt.get().durationSeconds();
      long gapSeconds =
          Duration.between(curr.activityEndTime(), next.activityStartTime()).getSeconds();

      if (transitDuration > gapSeconds) {
        violations.add(
            PlanViolation.ofVehicle(
                RuleId.H4_ASSIGNMENT_OVERLAP,
                vehicleId,
                next.activityStartTime(),
                "Vehicle "
                    + vehicleId
                    + " physical transit from "
                    + endLoc.id()
                    + " to "
                    + startLoc.id()
                    + " requires "
                    + transitDuration
                    + "s, but available schedule gap is only "
                    + gapSeconds
                    + "s"));
      }
    }
  }

  private Location resolveEndLocation(RouteActivity activity) {
    if (activity instanceof TripAssignment assign) {
      return assign.trip().toLocation();
    }
    if (activity instanceof ChargingSession session) {
      return context
          .findHub(session.hubId())
          .map(com.moveinsync.ev.domain.hub.Hub::location)
          .orElse(null);
    }
    return null;
  }

  private Location resolveStartLocation(RouteActivity activity) {
    if (activity instanceof TripAssignment assign) {
      return assign.trip().fromLocation();
    }
    if (activity instanceof ChargingSession session) {
      return context
          .findHub(session.hubId())
          .map(com.moveinsync.ev.domain.hub.Hub::location)
          .orElse(null);
    }
    return null;
  }
}
