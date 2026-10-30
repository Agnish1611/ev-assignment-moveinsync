package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Replays a plan on the current SoC. A failing trip on the emergency cab is lifted even inside the
 * lock window. A frozen trip on any other cab that no longer fits is backed up instead of moved.
 *
 * <p>Time O(T · H · P) for T trips, H hubs, and P plugs. Space O(T + C).
 */
final class ShiftReplay {

  record State(List<CabCursor> cursors, List<Trip> open, List<Trip> backed) {}

  static State replay(
      PlannerInput input,
      FleetPlan plan,
      int floorPercent,
      String emergencyCab,
      Instant now,
      int lockWindowSeconds,
      Set<String> alsoDrop) {
    Map<String, List<Trip>> byCab = assigned(input, plan);
    List<CabCursor> cursors = CabCursor.open(input);
    List<Trip> open = new ArrayList<>();
    List<Trip> backed = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    PlugCalendar plugs = new PlugCalendar();
    for (CabCursor cursor : cursors) {
      boolean emergency = cursor.vehicleId().equals(emergencyCab);
      for (Trip trip : byCab.getOrDefault(cursor.vehicleId(), List.of())) {
        seen.add(trip.id().value());
        if (alsoDrop.contains(trip.id().value())) {
          open.add(trip);
          continue;
        }
        AppendProbe probe = cursor.offerCharged(input, trip, floorPercent, plugs);
        if (probe.feasible()) {
          cursor.commit(input, probe, plugs);
        } else if (emergency || !TripRemoval.frozen(trip, now, lockWindowSeconds)) {
          open.add(trip);
        } else {
          backed.add(trip);
        }
      }
    }
    open.addAll(missing(input, plan, seen));
    return new State(cursors, open, backed);
  }

  private static Map<String, List<Trip>> assigned(PlannerInput input, FleetPlan plan) {
    Set<String> seen = new HashSet<>();
    Map<String, List<Trip>> byCab = new LinkedHashMap<>();
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      List<Trip> trips = new ArrayList<>();
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof TripAssignment assignment) {
          Optional<Trip> trip = find(input, assignment);
          if (trip.isPresent()) {
            String id = trip.orElseThrow().id().value();
            if (!seen.add(id)) {
              throw new IllegalArgumentException("trip is assigned more than once");
            }
            trips.add(trip.orElseThrow());
          }
        }
      }
      trips.sort(Comparator.comparing(Trip::startTs).thenComparing(trip -> trip.id().value()));
      byCab.merge(schedule.vehicleId().value(), trips, ShiftReplay::combine);
    }
    return byCab;
  }

  private static List<Trip> combine(List<Trip> left, List<Trip> right) {
    List<Trip> merged = new ArrayList<>(left);
    merged.addAll(right);
    merged.sort(Comparator.comparing(Trip::startTs).thenComparing(trip -> trip.id().value()));
    return merged;
  }

  private static Optional<Trip> find(PlannerInput input, TripAssignment assignment) {
    for (Trip trip : input.trips()) {
      if (trip.id().equals(assignment.trip().id())) {
        return Optional.of(trip);
      }
    }
    return Optional.empty();
  }

  private static List<Trip> missing(PlannerInput input, FleetPlan plan, Set<String> seen) {
    Set<String> parked = new HashSet<>();
    for (UnassignedTrip unassigned : plan.unassignedTrips()) {
      parked.add(unassigned.trip().id().value());
    }
    List<Trip> added = new ArrayList<>();
    for (Trip trip : input.trips()) {
      if (!seen.contains(trip.id().value()) && !parked.contains(trip.id().value())) {
        added.add(trip);
      }
    }
    return added;
  }
}
