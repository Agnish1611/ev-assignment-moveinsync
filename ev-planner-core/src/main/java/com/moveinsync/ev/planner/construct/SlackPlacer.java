package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Inserts lifted trips onto the cab that finishes with the most energy, hardest trip first.
 *
 * <p>Time O(R · C · T · H · P) for R lifted trips, C cabs, T trips already on a cab, H hubs, and P
 * plugs. Space O(T + C). A trial that does not fit leaves the other cabs untouched.
 */
final class SlackPlacer {

  static boolean place(
      List<CabCursor> cursors, List<Trip> open, PlannerInput input, int floorPercent) {
    List<Trip> pending = new ArrayList<>(open);
    pending.sort(byStart());
    while (!pending.isEmpty()) {
      Trip next = hardest(pending, cursors, input, floorPercent);
      Optional<Integer> chosen = bestCab(cursors, next, input, floorPercent);
      if (chosen.isEmpty()) {
        return false;
      }
      cursors.set(
          chosen.orElseThrow(), replay(cursors, chosen.orElseThrow(), next, input, floorPercent));
      if (!pending.remove(next)) {
        throw new IllegalStateException("lifted trip is no longer open");
      }
    }
    open.clear();
    return true;
  }

  private static Trip hardest(
      List<Trip> pending, List<CabCursor> cursors, PlannerInput input, int floorPercent) {
    Trip selected = pending.get(0);
    int fewest = Integer.MAX_VALUE;
    for (Trip trip : pending) {
      int feasible = 0;
      for (int index = 0; index < cursors.size(); index++) {
        if (fits(cursors, index, trip, input, floorPercent)) {
          feasible++;
        }
      }
      if (feasible < fewest) {
        selected = trip;
        fewest = feasible;
      }
    }
    return selected;
  }

  private static Optional<Integer> bestCab(
      List<CabCursor> cursors, Trip trip, PlannerInput input, int floorPercent) {
    int chosen = -1;
    long slack = Long.MIN_VALUE;
    for (int index = 0; index < cursors.size(); index++) {
      Optional<CabCursor> trial = trial(cursors, index, trip, input, floorPercent);
      if (trial.isPresent() && (chosen < 0 || trial.orElseThrow().socWh() > slack)) {
        chosen = index;
        slack = trial.orElseThrow().socWh();
      }
    }
    if (chosen < 0) {
      return Optional.empty();
    }
    return Optional.of(chosen);
  }

  private static CabCursor replay(
      List<CabCursor> cursors, int index, Trip extra, PlannerInput input, int floorPercent) {
    return trial(cursors, index, extra, input, floorPercent).orElseThrow();
  }

  private static boolean fits(
      List<CabCursor> cursors, int index, Trip extra, PlannerInput input, int floorPercent) {
    return trial(cursors, index, extra, input, floorPercent).isPresent();
  }

  private static Optional<CabCursor> trial(
      List<CabCursor> cursors, int index, Trip extra, PlannerInput input, int floorPercent) {
    CabCursor existing = cursors.get(index);
    List<Trip> sequence = tripsOf(existing);
    sequence.add(extra);
    sequence.sort(byStart());
    CabCursor cursor = fresh(existing);
    PlugCalendar plugs = others(cursors, existing.vehicleId());
    for (Trip trip : sequence) {
      AppendProbe probe = cursor.offerCharged(input, trip, floorPercent, plugs);
      if (!probe.feasible()) {
        return Optional.empty();
      }
      cursor.commit(input, probe, plugs);
    }
    return Optional.of(cursor);
  }

  private static CabCursor fresh(CabCursor existing) {
    VehicleSchedule schedule = existing.toSchedule();
    return new CabCursor(
        new CabShift(
            schedule.vehicle(),
            schedule.initialLocation(),
            schedule.initialSocWh(),
            schedule.availableFrom()));
  }

  private static List<Trip> tripsOf(CabCursor cursor) {
    List<Trip> trips = new ArrayList<>();
    for (RouteActivity activity : cursor.toSchedule().activities()) {
      if (activity instanceof TripAssignment assignment) {
        trips.add(assignment.trip());
      }
    }
    return trips;
  }

  private static PlugCalendar others(List<CabCursor> cursors, String skip) {
    PlugCalendar plugs = new PlugCalendar();
    for (CabCursor cursor : cursors) {
      if (cursor.vehicleId().equals(skip)) {
        continue;
      }
      for (RouteActivity activity : cursor.toSchedule().activities()) {
        if (activity instanceof ChargingSession session) {
          plugs.book(session);
        }
      }
    }
    return plugs;
  }

  private static Comparator<Trip> byStart() {
    return Comparator.comparing(Trip::startTs).thenComparing(trip -> trip.id().value());
  }
}
