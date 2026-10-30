package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Inserts trips by regret-k, or in start-time order when {@code regretDepth} is 0.
 *
 * <p>Time O(R^2 · C · H) for regret and O(R · C · H) for start-time order, where R is the number of
 * trips still open, C is the number of cabs, and H is the number of hubs. Space O(R + C). The
 * deadline is polled before each insertion. A trip that does not fit stays in the pool until no
 * remaining trip fits, so a later trip can use a cab that an earlier insertion just freed in
 * location or charge.
 */
final class RegretInserter {

  static boolean insert(
      PlannerInput input,
      SolverConfig config,
      Clock clock,
      List<CabCursor> cursors,
      List<Trip> remaining,
      List<UnassignedTrip> unassigned,
      int regretDepth) {
    boolean exhausted = false;
    while (!remaining.isEmpty()) {
      if (!clock.instant().isBefore(config.deadline())) {
        UnassignedReasons.park(
            remaining,
            unassigned,
            UnassignedReasonCode.SAFE_PATCH_FALLBACK,
            UnassignedReasons.DEADLINE);
        exhausted = true;
        break;
      }
      if (!step(input, config, cursors, remaining, unassigned, regretDepth)) {
        break;
      }
    }
    return exhausted;
  }

  private static boolean step(
      PlannerInput input,
      SolverConfig config,
      List<CabCursor> cursors,
      List<Trip> remaining,
      List<UnassignedTrip> unassigned,
      int regretDepth) {
    Optional<Selection> chosen = choose(input, config, cursors, remaining, regretDepth);
    if (chosen.isEmpty()) {
      if (regretDepth <= 0) {
        Trip skipped = remaining.remove(0);
        unassigned.add(UnassignedReasons.explain(input, config, cursors, skipped));
        return true;
      }
      UnassignedReasons.parkBlocked(input, config, cursors, remaining, unassigned);
      return false;
    }
    Selection selection = chosen.orElseThrow();
    selection.cursor().commit(selection.assignment());
    if (!remaining.remove(selection.trip())) {
      throw new IllegalStateException("selected trip was not waiting to be inserted");
    }
    return true;
  }

  private static Optional<Selection> choose(
      PlannerInput input,
      SolverConfig config,
      List<CabCursor> cursors,
      List<Trip> remaining,
      int regretDepth) {
    if (regretDepth <= 0) {
      return score(input, config, cursors, remaining.get(0), 1);
    }
    Optional<Selection> best = Optional.empty();
    for (Trip trip : remaining) {
      Optional<Selection> candidate = score(input, config, cursors, trip, regretDepth);
      if (candidate.isPresent()
          && (best.isEmpty() || better(candidate.orElseThrow(), best.orElseThrow()))) {
        best = candidate;
      }
    }
    return best;
  }

  private static Optional<Selection> score(
      PlannerInput input,
      SolverConfig config,
      List<CabCursor> cursors,
      Trip trip,
      int regretDepth) {
    List<Offer> offers = new ArrayList<>();
    for (CabCursor cursor : cursors) {
      AppendProbe probe = cursor.offer(input, trip, config.planningFloorPercent());
      if (probe.feasible()) {
        offers.add(new Offer(cursor, probe.assignment().orElseThrow(), probe.energyWh()));
      }
    }
    if (offers.isEmpty()) {
      return Optional.empty();
    }
    offers.sort(
        Comparator.comparingLong(Offer::energyWh)
            .thenComparing(offer -> offer.cursor().vehicleId()));
    Offer best = offers.get(0);
    return Optional.of(
        new Selection(trip, best.cursor(), best.assignment(), rank(offers, regretDepth)));
  }

  private static long rank(List<Offer> offers, int regretDepth) {
    if (regretDepth <= 1) {
      return -offers.get(0).energyWh();
    }
    if (offers.size() < regretDepth) {
      return Long.MAX_VALUE / 4L - offers.size();
    }
    return Math.subtractExact(offers.get(regretDepth - 1).energyWh(), offers.get(0).energyWh());
  }

  private static boolean better(Selection candidate, Selection incumbent) {
    int byScore = Long.compare(candidate.score(), incumbent.score());
    if (byScore != 0) {
      return byScore > 0;
    }
    int byStart = candidate.trip().startTs().compareTo(incumbent.trip().startTs());
    if (byStart != 0) {
      return byStart < 0;
    }
    return candidate.trip().id().value().compareTo(incumbent.trip().id().value()) < 0;
  }

  private record Offer(CabCursor cursor, TripAssignment assignment, long energyWh) {}

  private record Selection(Trip trip, CabCursor cursor, TripAssignment assignment, long score) {}
}
