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
 * Regret-k insertion that remembers each open trip's feasible cabs.
 *
 * <p>A commit that does not book a plug changes only that cab, so each open trip replaces one
 * offer. A commit that books a plug rescores every open trip, because the calendar can block other
 * cabs. Worst-case time is still O(R² · C · H · P). The no-booking case is O(R · C · H · P + R² ·
 * C). Space O(R · C). The deadline is polled before each insertion.
 */
final class RegretBoard {

  private static final Comparator<Offer> BY_COST =
      Comparator.comparingLong((Offer offer) -> offer.probe().energyWh())
          .thenComparing(offer -> offer.cursor().vehicleId());

  private final List<Row> rows;

  private RegretBoard(List<Row> rows) {
    this.rows = rows;
  }

  static boolean fill(
      PlannerInput input,
      SolverConfig config,
      Clock clock,
      List<CabCursor> cursors,
      List<Trip> remaining,
      List<UnassignedTrip> unassigned,
      PlugCalendar plugs,
      int regretDepth) {
    RegretBoard board = open(input, config, cursors, remaining, plugs, regretDepth);
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
      if (!board.step(input, config, cursors, remaining, unassigned, plugs, regretDepth)) {
        break;
      }
    }
    return exhausted;
  }

  private static RegretBoard open(
      PlannerInput input,
      SolverConfig config,
      List<CabCursor> cursors,
      List<Trip> remaining,
      PlugCalendar plugs,
      int regretDepth) {
    List<Row> rows = new ArrayList<>();
    for (Trip trip : remaining) {
      rows.add(Row.scored(input, config, cursors, trip, plugs, regretDepth));
    }
    return new RegretBoard(rows);
  }

  private boolean step(
      PlannerInput input,
      SolverConfig config,
      List<CabCursor> cursors,
      List<Trip> remaining,
      List<UnassignedTrip> unassigned,
      PlugCalendar plugs,
      int regretDepth) {
    Optional<Pick> chosen = best();
    if (chosen.isEmpty()) {
      UnassignedReasons.parkBlocked(input, config, cursors, remaining, unassigned, plugs);
      return false;
    }
    Pick pick = chosen.orElseThrow();
    pick.cursor().commit(input, pick.probe(), plugs);
    if (!remaining.remove(pick.trip())) {
      throw new IllegalStateException("selected trip was not waiting to be inserted");
    }
    note(pick, input, config, cursors, plugs, regretDepth);
    return true;
  }

  private Optional<Pick> best() {
    Optional<Pick> chosen = Optional.empty();
    for (Row row : rows) {
      Optional<Pick> candidate = row.pick();
      if (candidate.isPresent()
          && (chosen.isEmpty() || outranks(candidate.orElseThrow(), chosen.orElseThrow()))) {
        chosen = candidate;
      }
    }
    return chosen;
  }

  private void note(
      Pick pick,
      PlannerInput input,
      SolverConfig config,
      List<CabCursor> cursors,
      PlugCalendar plugs,
      int regretDepth) {
    drop(pick.trip());
    if (pick.probe().charge().isPresent()) {
      for (Row row : rows) {
        row.rescore(input, config, cursors, plugs, regretDepth);
      }
      return;
    }
    for (Row row : rows) {
      row.refresh(pick.cursor(), input, config, plugs, regretDepth);
    }
  }

  private void drop(Trip trip) {
    for (int index = 0; index < rows.size(); index++) {
      if (rows.get(index).trip().id().equals(trip.id())) {
        rows.remove(index);
        return;
      }
    }
    throw new IllegalStateException("selected trip was not on the regret board");
  }

  private static boolean outranks(Pick candidate, Pick incumbent) {
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

  private record Offer(CabCursor cursor, AppendProbe probe) {}

  private record Pick(Trip trip, CabCursor cursor, AppendProbe probe, long score) {}

  private static final class Row {

    private final Trip trip;
    private final List<Offer> offers;
    private long score;

    private Row(Trip trip, List<Offer> offers, long score) {
      this.trip = trip;
      this.offers = offers;
      this.score = score;
    }

    private static Row scored(
        PlannerInput input,
        SolverConfig config,
        List<CabCursor> cursors,
        Trip trip,
        PlugCalendar plugs,
        int regretDepth) {
      Row row = new Row(trip, new ArrayList<>(), 0L);
      row.rescore(input, config, cursors, plugs, regretDepth);
      return row;
    }

    private Trip trip() {
      return trip;
    }

    private Optional<Pick> pick() {
      if (offers.isEmpty()) {
        return Optional.empty();
      }
      Offer best = offers.get(0);
      return Optional.of(new Pick(trip, best.cursor(), best.probe(), score));
    }

    private void rescore(
        PlannerInput input,
        SolverConfig config,
        List<CabCursor> cursors,
        PlugCalendar plugs,
        int regretDepth) {
      offers.clear();
      for (CabCursor cursor : cursors) {
        AppendProbe probe = cursor.offerCharged(input, trip, config.planningFloorPercent(), plugs);
        if (probe.feasible()) {
          offers.add(new Offer(cursor, probe));
        }
      }
      offers.sort(BY_COST);
      score = offers.isEmpty() ? 0L : rank(offers, regretDepth);
    }

    private void refresh(
        CabCursor cursor,
        PlannerInput input,
        SolverConfig config,
        PlugCalendar plugs,
        int regretDepth) {
      remove(cursor.vehicleId());
      AppendProbe probe = cursor.offerCharged(input, trip, config.planningFloorPercent(), plugs);
      if (probe.feasible()) {
        insert(new Offer(cursor, probe));
      }
      score = offers.isEmpty() ? 0L : rank(offers, regretDepth);
    }

    private void insert(Offer offer) {
      int index = 0;
      while (index < offers.size() && BY_COST.compare(offers.get(index), offer) <= 0) {
        index++;
      }
      offers.add(index, offer);
    }

    private void remove(String vehicleId) {
      for (int index = 0; index < offers.size(); index++) {
        if (offers.get(index).cursor().vehicleId().equals(vehicleId)) {
          offers.remove(index);
          return;
        }
      }
    }
  }
}
