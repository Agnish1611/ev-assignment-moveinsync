package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unfrozen trips on other cabs, nearest in pickup time to the trip that has to move.
 *
 * <p>Time O(T log T). Space O(T).
 */
final class NeighbourRing {

  static Set<String> closest(
      PlannerInput input,
      FleetPlan plan,
      List<Trip> open,
      Instant now,
      int lockWindowSeconds,
      String emergencyCab,
      int limit) {
    if (open.isEmpty() || limit <= 0) {
      return Set.of();
    }
    Trip anchor = earliest(open);
    Map<String, String> assigned = AssignmentDiff.vehicles(plan);
    Set<String> lifted = ids(open);
    List<Trip> candidates = new ArrayList<>();
    for (Trip trip : input.trips()) {
      if (movable(trip, assigned, lifted, emergencyCab, now, lockWindowSeconds)) {
        candidates.add(trip);
      }
    }
    candidates.sort(near(anchor));
    Set<String> chosen = new LinkedHashSet<>();
    for (int index = 0; index < candidates.size() && chosen.size() < limit; index++) {
      chosen.add(candidates.get(index).id().value());
    }
    return chosen;
  }

  private static boolean movable(
      Trip trip,
      Map<String, String> assigned,
      Set<String> lifted,
      String emergencyCab,
      Instant now,
      int lockWindowSeconds) {
    String cab = assigned.getOrDefault(trip.id().value(), "");
    if (cab.isEmpty() || cab.equals(emergencyCab) || lifted.contains(trip.id().value())) {
      return false;
    }
    return !TripRemoval.frozen(trip, now, lockWindowSeconds);
  }

  private static Trip earliest(List<Trip> open) {
    Trip anchor = open.get(0);
    for (Trip trip : open) {
      if (trip.startTs().isBefore(anchor.startTs())
          || (trip.startTs().equals(anchor.startTs())
              && trip.id().value().compareTo(anchor.id().value()) < 0)) {
        anchor = trip;
      }
    }
    return anchor;
  }

  private static Set<String> ids(List<Trip> trips) {
    Set<String> ids = new LinkedHashSet<>();
    for (Trip trip : trips) {
      ids.add(trip.id().value());
    }
    return ids;
  }

  private static Comparator<Trip> near(Trip anchor) {
    return Comparator.comparingLong((Trip trip) -> gap(trip, anchor))
        .thenComparing(trip -> trip.id().value());
  }

  private static long gap(Trip trip, Trip anchor) {
    long delta = trip.startTs().getEpochSecond() - anchor.startTs().getEpochSecond();
    if (delta < 0L) {
      return -delta;
    }
    return delta;
  }
}
