package com.moveinsync.ev.planner.construct;

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
 * <p>Time O(R^2 · C · H · P) for regret and O(R · C · H · P) for start-time order, where R is the
 * number of trips still open, C is the number of cabs, H is the number of hubs, and P is the number
 * of plugs considered when a direct append needs a charge stop. Space O(R + C). The deadline is
 * polled before each insertion.
 */
final class RegretInserter {

  static boolean insert(
      PlannerInput input,
      SolverConfig config,
      Clock clock,
      List<CabCursor> cursors,
      List<Trip> remaining,
      List<UnassignedTrip> unassigned,
      PlugCalendar plugs,
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
      if (!step(input, config, cursors, remaining, unassigned, plugs, regretDepth)) {
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
      PlugCalendar plugs,
      int regretDepth) {
    Optional<Selection> chosen = choose(input, config, cursors, remaining, plugs, regretDepth);
    if (chosen.isEmpty()) {
      if (regretDepth <= 0) {
        Trip skipped = remaining.remove(0);
        unassigned.add(UnassignedReasons.explain(input, config, cursors, skipped, plugs));
        return true;
      }
      UnassignedReasons.parkBlocked(input, config, cursors, remaining, unassigned, plugs);
      return false;
    }
    Selection selection = chosen.orElseThrow();
    selection.cursor().commit(input, selection.probe(), plugs);
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
      PlugCalendar plugs,
      int regretDepth) {
    if (regretDepth <= 0) {
      return score(input, config, cursors, remaining.get(0), plugs, 1);
    }
    Optional<Selection> best = Optional.empty();
    for (Trip trip : remaining) {
      Optional<Selection> candidate = score(input, config, cursors, trip, plugs, regretDepth);
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
      PlugCalendar plugs,
      int regretDepth) {
    List<Offer> offers = new ArrayList<>();
    for (CabCursor cursor : cursors) {
      AppendProbe probe = cursor.offerCharged(input, trip, config.planningFloorPercent(), plugs);
      if (probe.feasible()) {
        offers.add(new Offer(cursor, probe));
      }
    }
    if (offers.isEmpty()) {
      return Optional.empty();
    }
    offers.sort(
        Comparator.comparingLong((Offer offer) -> offer.probe().energyWh())
            .thenComparing(offer -> offer.cursor().vehicleId()));
    Offer best = offers.get(0);
    return Optional.of(new Selection(trip, best.cursor(), best.probe(), rank(offers, regretDepth)));
  }

  private static long rank(List<Offer> offers, int regretDepth) {
    if (regretDepth <= 1) {
      return -offers.get(0).probe().energyWh();
    }
    if (offers.size() < regretDepth) {
      return Long.MAX_VALUE / 4L - offers.size();
    }
    return Math.subtractExact(
        offers.get(regretDepth - 1).probe().energyWh(), offers.get(0).probe().energyWh());
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

  private record Offer(CabCursor cursor, AppendProbe probe) {}

  private record Selection(Trip trip, CabCursor cursor, AppendProbe probe, long score) {}
}
