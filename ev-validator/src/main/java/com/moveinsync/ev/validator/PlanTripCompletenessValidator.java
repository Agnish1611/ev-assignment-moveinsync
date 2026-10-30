package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates trip partition completeness (every trip assigned or unassigned, no duplicates, no
 * unknown trips).
 */
final class PlanTripCompletenessValidator {

  private PlanTripCompletenessValidator() {}

  static void checkTripCompleteness(
      ValidationInput input, FleetPlan candidate, List<PlanViolation> violations) {
    Set<TripId> assignedTripIds = new HashSet<>();
    for (VehicleSchedule schedule : candidate.vehicleSchedules()) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof TripAssignment assign) {
          assignedTripIds.add(assign.trip().id());
        }
      }
    }

    Set<TripId> unassignedTripIds = new HashSet<>();
    for (UnassignedTrip unassigned : candidate.unassignedTrips()) {
      TripId tripId = unassigned.trip().id();
      if (input.findTrip(tripId).isEmpty()) {
        violations.add(
            PlanViolation.ofTrip(
                RuleId.TRIP_UNKNOWN,
                tripId,
                null,
                unassigned.trip().startTs(),
                "Unassigned trip " + tripId + " is not registered in ground truth"));
      }
      if (assignedTripIds.contains(tripId)) {
        violations.add(
            PlanViolation.ofTrip(
                RuleId.TRIP_ASSIGNED_AND_UNASSIGNED,
                tripId,
                null,
                unassigned.trip().startTs(),
                "Trip "
                    + tripId
                    + " appears simultaneously in both assigned schedules and unassigned list"));
      }
      unassignedTripIds.add(tripId);
    }

    for (Trip requiredTrip : input.trips().values()) {
      TripId id = requiredTrip.id();
      if (!assignedTripIds.contains(id) && !unassignedTripIds.contains(id)) {
        violations.add(
            PlanViolation.ofTrip(
                RuleId.TRIP_OMITTED,
                id,
                null,
                requiredTrip.startTs(),
                "Required trip "
                    + id
                    + " is omitted from both assigned schedules and unassigned list"));
      }
    }
  }
}
