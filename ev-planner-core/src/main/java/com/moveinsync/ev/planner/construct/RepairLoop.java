package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.operator.DestroyedPlan;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Puts lifted trips back onto the surviving routes.
 *
 * <p>Time follows {@link RegretInserter}. Space O(T + C). A deadline that has already passed
 * returns the surviving plan unchanged.
 */
final class RepairLoop {

  static FleetPlan repair(
      PlannerInput input,
      DestroyedPlan destroyed,
      SolverConfig config,
      SplittableRandom random,
      Clock clock,
      int regretDepth) {
    Objects.requireNonNull(input, "input must not be null");
    Objects.requireNonNull(destroyed, "destroyed must not be null");
    Objects.requireNonNull(config, "config must not be null");
    Objects.requireNonNull(random, "random must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
    if (!clock.instant().isBefore(config.deadline())) {
      return destroyed.kept();
    }
    FleetPlan kept = destroyed.kept();
    List<CabCursor> cursors = RouteReplay.load(input, kept, config.planningFloorPercent());
    List<Trip> remaining = new ArrayList<>();
    Set<String> wanted = wanted(input, destroyed, remaining);
    List<UnassignedTrip> unassigned = carryOver(kept, wanted);
    RegretInserter.insert(input, config, clock, cursors, remaining, unassigned, regretDepth);
    return PlanAssembler.assemble(input, kept.planId(), kept.seed(), cursors, unassigned);
  }

  private static Set<String> wanted(
      PlannerInput input, DestroyedPlan destroyed, List<Trip> remaining) {
    Set<String> wanted = new HashSet<>();
    for (TripId id : destroyed.removedTripIds()) {
      if (!wanted.add(id.value())) {
        throw new IllegalArgumentException("duplicate removed trip id");
      }
    }
    for (Trip trip : input.trips()) {
      if (wanted.remove(trip.id().value())) {
        remaining.add(trip);
      }
    }
    if (!wanted.isEmpty()) {
      throw new IllegalArgumentException("removed trip is not in the planner input");
    }
    return ids(remaining);
  }

  private static Set<String> ids(List<Trip> trips) {
    Set<String> ids = new HashSet<>();
    for (Trip trip : trips) {
      ids.add(trip.id().value());
    }
    return ids;
  }

  private static List<UnassignedTrip> carryOver(FleetPlan kept, Set<String> removed) {
    List<UnassignedTrip> unassigned = new ArrayList<>();
    for (UnassignedTrip existing : kept.unassignedTrips()) {
      if (!removed.contains(existing.trip().id().value())) {
        unassigned.add(existing);
      }
    }
    return unassigned;
  }
}
