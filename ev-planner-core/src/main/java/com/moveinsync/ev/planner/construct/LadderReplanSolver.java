package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.objective.ObjectiveScore;
import com.moveinsync.ev.planner.solve.DriftEvent;
import com.moveinsync.ev.planner.solve.ReplanSolver;
import com.moveinsync.ev.planner.solve.SolveResult;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Deadline-aware repair ladder. Tier 0 keeps the trip on the same cab when a charge still makes the
 * pickup, otherwise moves it to the cab with the most energy left. Tier 1 and tier 2 lift the
 * nearest unfrozen trips and repair again. A missed deadline, or a repair that would change more
 * than {@link SolverConfig#DEFAULT_REPLAN_CHANGE_BUDGET} assignments, sends only the trips that
 * still do not fit to backup.
 *
 * <p>Time O(T^2 · C · H · P) for T trips, C cabs, H hubs, and P plugs. Space O(T + C). The deadline
 * is polled before each tier. Tier 2 is kept only when it beats the incumbent and the plan id is
 * still the one captured at the start of the call.
 */
public final class LadderReplanSolver implements ReplanSolver {

  static final String SAFE_EXPLANATION = "safe patch sent the trip to backup";

  record Attempt(List<CabCursor> cursors, List<UnassignedTrip> unassigned, boolean complete) {}

  @Override
  public SolveResult replan(
      PlannerInput input, FleetPlan current, DriftEvent event, SolverConfig config, Clock clock) {
    Objects.requireNonNull(input, "input must not be null");
    Objects.requireNonNull(current, "current plan must not be null");
    Objects.requireNonNull(event, "event must not be null");
    Objects.requireNonNull(config, "config must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
    if (!current.parsedTenantId().equals(input.tenantId())) {
      throw new IllegalArgumentException("current plan tenant does not match planner tenant");
    }
    return climb(input, current, event, config, clock, current.planId());
  }

  private static SolveResult climb(
      PlannerInput input,
      FleetPlan current,
      DriftEvent event,
      SolverConfig config,
      Clock clock,
      String version) {
    Instant now = clock.instant();
    String emergency = emergencyCab(input, event);
    Attempt safe = patch(input, current, config, emergency, now);
    if (!now.isBefore(config.deadline())) {
      return publish(input, config, clock, current, safe, true);
    }
    List<Trip> opened = preview(input, current, config, emergency, now);
    Attempt best =
        keep(null, repair(input, current, config, emergency, now, Set.of()), current, config);
    if (!clock.instant().isBefore(config.deadline())) {
      return stop(input, config, clock, current, best, safe);
    }
    Set<String> first =
        NeighbourRing.closest(
            input, current, opened, now, config.lockWindowSeconds(), emergency, 1);
    best = keep(best, repair(input, current, config, emergency, now, first), current, config);
    if (!clock.instant().isBefore(config.deadline())) {
      return stop(input, config, clock, current, best, safe);
    }
    Set<String> second =
        NeighbourRing.closest(
            input, current, opened, now, config.lockWindowSeconds(), emergency, 2);
    best =
        polish(
            best, repair(input, current, config, emergency, now, second), version, current, config);
    if (best != null) {
      return publish(input, config, clock, current, best, false);
    }
    return publish(input, config, clock, current, safe, false);
  }

  private static SolveResult stop(
      PlannerInput input,
      SolverConfig config,
      Clock clock,
      FleetPlan current,
      Attempt best,
      Attempt safe) {
    if (best != null) {
      return publish(input, config, clock, current, best, false);
    }
    return publish(input, config, clock, current, safe, true);
  }

  static Attempt polish(
      Attempt best, Attempt candidate, String version, FleetPlan current, SolverConfig config) {
    if (!version.equals(current.planId())) {
      return best;
    }
    return keep(best, candidate, current, config);
  }

  private static Attempt keep(
      Attempt best, Attempt candidate, FleetPlan current, SolverConfig config) {
    if (!candidate.complete()) {
      return best;
    }
    if (best == null || better(candidate, best, current, config)) {
      return candidate;
    }
    return best;
  }

  private static boolean better(
      Attempt candidate, Attempt incumbent, FleetPlan current, SolverConfig config) {
    return score(candidate, current)
            .comparePreferringFewerBackups(score(incumbent, current), config.changePenaltyMicros())
        < 0;
  }

  private static ObjectiveScore score(Attempt attempt, FleetPlan current) {
    return new ObjectiveScore(
        attempt.unassigned().size(),
        AssignmentDiff.changes(current, attempt.cursors()),
        charging(attempt.cursors()));
  }

  private static long charging(List<CabCursor> cursors) {
    long total = 0L;
    for (CabCursor cursor : cursors) {
      for (RouteActivity activity : cursor.toSchedule().activities()) {
        if (activity instanceof ChargingSession session) {
          total = Math.addExact(total, session.costMicros());
        }
      }
    }
    return total;
  }

  private static List<Trip> preview(
      PlannerInput input, FleetPlan current, SolverConfig config, String emergency, Instant now) {
    return ShiftReplay.replay(
            input,
            current,
            config.planningFloorPercent(),
            emergency,
            now,
            config.lockWindowSeconds(),
            Set.of())
        .open();
  }

  private static Attempt repair(
      PlannerInput input,
      FleetPlan current,
      SolverConfig config,
      String emergency,
      Instant now,
      Set<String> drop) {
    ShiftReplay.State state =
        ShiftReplay.replay(
            input,
            current,
            config.planningFloorPercent(),
            emergency,
            now,
            config.lockWindowSeconds(),
            drop);
    List<Trip> open = new ArrayList<>(state.open());
    boolean placed = SlackPlacer.place(state.cursors(), open, input, config.planningFloorPercent());
    if (!placed || !state.backed().isEmpty()) {
      return skip();
    }
    if (AssignmentDiff.changes(current, state.cursors())
        > SolverConfig.DEFAULT_REPLAN_CHANGE_BUDGET) {
      return skip();
    }
    return new Attempt(state.cursors(), parked(input, current), true);
  }

  private static Attempt patch(
      PlannerInput input, FleetPlan current, SolverConfig config, String emergency, Instant now) {
    ShiftReplay.State state =
        ShiftReplay.replay(
            input,
            current,
            config.planningFloorPercent(),
            emergency,
            now,
            config.lockWindowSeconds(),
            Set.of());
    List<Trip> failed = new ArrayList<>(state.open());
    failed.addAll(state.backed());
    return new Attempt(state.cursors(), backedUp(input, current, failed), false);
  }

  private static Attempt skip() {
    return new Attempt(List.of(), List.of(), false);
  }

  private static String emergencyCab(PlannerInput input, DriftEvent event) {
    if (event.kind() != DriftEvent.Kind.BATTERY && event.kind() != DriftEvent.Kind.LATE_CAB) {
      return "";
    }
    for (CabShift cab : input.cabs()) {
      if (cab.vehicle().id().value().equals(event.subjectId())) {
        return event.subjectId();
      }
    }
    throw new IllegalArgumentException("drift subject is not a cab: " + event.subjectId());
  }

  private static List<UnassignedTrip> parked(PlannerInput input, FleetPlan current) {
    return backedUp(input, current, List.of());
  }

  private static List<UnassignedTrip> backedUp(
      PlannerInput input, FleetPlan current, List<Trip> failed) {
    List<UnassignedTrip> rows = new ArrayList<>();
    for (Trip trip : failed) {
      rows.add(UnassignedTrip.of(trip, UnassignedReasonCode.SAFE_PATCH_FALLBACK, SAFE_EXPLANATION));
    }
    for (UnassignedTrip prior : current.unassignedTrips()) {
      Optional<Trip> live = live(input, prior.trip().id().value());
      if (live.isPresent() && !contains(failed, live.orElseThrow())) {
        rows.add(UnassignedTrip.of(live.orElseThrow(), prior.reasonCode(), prior.explanation()));
      }
    }
    return rows;
  }

  private static boolean contains(List<Trip> trips, Trip trip) {
    for (Trip candidate : trips) {
      if (candidate.id().equals(trip.id())) {
        return true;
      }
    }
    return false;
  }

  private static Optional<Trip> live(PlannerInput input, String tripId) {
    for (Trip trip : input.trips()) {
      if (trip.id().value().equals(tripId)) {
        return Optional.of(trip);
      }
    }
    return Optional.empty();
  }

  private static SolveResult publish(
      PlannerInput input,
      SolverConfig config,
      Clock clock,
      FleetPlan current,
      Attempt attempt,
      boolean exhausted) {
    FleetPlan draft =
        PlanAssembler.assemble(
            input,
            "replan-" + input.inputHash(),
            config.seed(),
            attempt.cursors(),
            attempt.unassigned());
    FlowRetimer.Retimed retimed = FlowRetimer.retime(input, draft, config, clock);
    ObjectiveScore score =
        new ObjectiveScore(
            retimed.plan().totalUnassignedTrips(),
            AssignmentDiff.changes(current, retimed.plan()),
            retimed.plan().totalChargingCostMicros());
    return new SolveResult(
        retimed.plan(), score, exhausted || retimed.deadlineExhausted(), retimed.bound());
  }
}
