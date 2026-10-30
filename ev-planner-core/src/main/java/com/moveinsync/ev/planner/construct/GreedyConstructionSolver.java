package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.objective.ObjectiveScore;
import com.moveinsync.ev.planner.solve.BackupFleetSolver;
import com.moveinsync.ev.planner.solve.FleetSolver;
import com.moveinsync.ev.planner.solve.SolveResult;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Regret-2 construction. A trip that fails on energy or reserve is offered one charge stop when a
 * hub session fits before the pickup. Trips that still do not fit, and trips still open when the
 * deadline passes, go to backup.
 *
 * <p>Time O(T^2 · C · H · P) for T trips, C cabs, H hubs, and P plugs. Space O(T + C). The deadline
 * is polled before each insertion. A deadline that has already passed returns the backup plan.
 */
public final class GreedyConstructionSolver implements FleetSolver {

  @Override
  public SolveResult solve(PlannerInput input, SolverConfig config, Clock clock) {
    Objects.requireNonNull(input, "input must not be null");
    Objects.requireNonNull(config, "config must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
    if (!clock.instant().isBefore(config.deadline())) {
      Clock late = Clock.fixed(config.deadline(), clock.getZone());
      return new BackupFleetSolver().solve(input, config, late);
    }
    List<CabCursor> cursors = CabCursor.open(input);
    List<Trip> remaining = new ArrayList<>(input.trips());
    List<UnassignedTrip> unassigned = new ArrayList<>();
    PlugCalendar plugs = new PlugCalendar();
    boolean exhausted =
        RegretInserter.insert(input, config, clock, cursors, remaining, unassigned, plugs, 2);
    FleetPlan plan =
        PlanAssembler.assemble(
            input, "greedy-" + input.inputHash(), config.seed(), cursors, unassigned);
    ObjectiveScore score =
        new ObjectiveScore(unassigned.size(), 0L, plan.totalChargingCostMicros());
    return new SolveResult(plan, score, exhausted);
  }
}
