package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Rebuilds cab cursors from a plan by simulating each surviving trip again. Ground truth comes from
 * {@link PlannerInput}, not from the claims on the plan.
 */
final class RouteReplay {

  enum Mode {
    STRICT,
    RELAX
  }

  static List<CabCursor> load(PlannerInput input, FleetPlan plan, int floorPercent) {
    requireSameTenant(input, plan);
    return replay(input, plan, floorPercent, Set.of(), Mode.STRICT, trip -> false).cursors();
  }

  static Outcome relax(
      PlannerInput input,
      FleetPlan plan,
      int floorPercent,
      Set<String> drop,
      Predicate<Trip> frozen) {
    requireSameTenant(input, plan);
    return replay(input, plan, floorPercent, drop, Mode.RELAX, frozen);
  }

  static Trip requireTrip(PlannerInput input, TripId id) {
    for (Trip trip : input.trips()) {
      if (trip.id().equals(id)) {
        return trip;
      }
    }
    throw new IllegalArgumentException("plan trip is not in the planner input");
  }

  private static Outcome replay(
      PlannerInput input,
      FleetPlan plan,
      int floorPercent,
      Set<String> drop,
      Mode mode,
      Predicate<Trip> frozen) {
    Map<String, VehicleSchedule> byCab = index(plan);
    List<CabCursor> cursors = new ArrayList<>();
    List<Trip> lifted = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (CabShift cab : input.cabs()) {
      CabCursor cursor = new CabCursor(cab);
      VehicleSchedule schedule = byCab.remove(cab.vehicle().id().value());
      if (schedule != null
          && !apply(input, cursor, schedule, floorPercent, drop, mode, frozen, lifted, seen)) {
        return Outcome.halt();
      }
      cursors.add(cursor);
    }
    if (!byCab.isEmpty()) {
      throw new IllegalArgumentException("plan vehicle is not in the planner input");
    }
    return Outcome.kept(cursors, lifted);
  }

  private static boolean apply(
      PlannerInput input,
      CabCursor cursor,
      VehicleSchedule schedule,
      int floorPercent,
      Set<String> drop,
      Mode mode,
      Predicate<Trip> frozen,
      List<Trip> lifted,
      Set<String> seen) {
    for (RouteActivity activity : schedule.activities()) {
      if (!keep(input, cursor, activity, floorPercent, drop, mode, frozen, lifted, seen)) {
        return false;
      }
    }
    return true;
  }

  private static boolean keep(
      PlannerInput input,
      CabCursor cursor,
      RouteActivity activity,
      int floorPercent,
      Set<String> drop,
      Mode mode,
      Predicate<Trip> frozen,
      List<Trip> lifted,
      Set<String> seen) {
    if (!(activity instanceof TripAssignment assignment)) {
      throw new IllegalArgumentException("charging sessions are not rebuilt by this operator");
    }
    Trip trip = requireTrip(input, assignment.trip().id());
    if (!seen.add(trip.id().value())) {
      throw new IllegalArgumentException("trip is assigned more than once");
    }
    if (drop.contains(trip.id().value())) {
      lifted.add(trip);
      return true;
    }
    AppendProbe probe = cursor.offer(input, trip, floorPercent);
    if (probe.feasible()) {
      cursor.commit(probe.assignment().orElseThrow());
      return true;
    }
    if (mode == Mode.STRICT) {
      throw new IllegalArgumentException("plan assignment is not feasible for this input");
    }
    if (frozen.test(trip)) {
      return false;
    }
    lifted.add(trip);
    return true;
  }

  private static Map<String, VehicleSchedule> index(FleetPlan plan) {
    Map<String, VehicleSchedule> byCab = new LinkedHashMap<>();
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      String id = schedule.vehicleId().value();
      if (byCab.put(id, schedule) != null) {
        throw new IllegalArgumentException("duplicate vehicle schedule");
      }
    }
    return byCab;
  }

  private static void requireSameTenant(PlannerInput input, FleetPlan plan) {
    if (!plan.parsedTenantId().equals(input.tenantId())) {
      throw new IllegalArgumentException("plan tenant does not match planner tenant");
    }
  }

  static final class Outcome {
    private final boolean aborted;
    private final List<CabCursor> cursors;
    private final List<Trip> lifted;

    private Outcome(boolean aborted, List<CabCursor> cursors, List<Trip> lifted) {
      this.aborted = aborted;
      this.cursors = cursors;
      this.lifted = lifted;
    }

    static Outcome halt() {
      return new Outcome(true, List.of(), List.of());
    }

    static Outcome kept(List<CabCursor> cursors, List<Trip> lifted) {
      return new Outcome(false, List.copyOf(cursors), List.copyOf(lifted));
    }

    boolean aborted() {
      return aborted;
    }

    List<CabCursor> cursors() {
      return cursors;
    }

    List<Trip> lifted() {
      return lifted;
    }
  }
}
