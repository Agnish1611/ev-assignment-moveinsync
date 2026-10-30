package com.moveinsync.ev.planner.solve;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.objective.ObjectiveScore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Last-resort solver. Every trip goes to backup and every cab is given an empty schedule.
 *
 * <p>Time O(T + C) after the input has already been sorted. Space O(T + C). The deadline is polled
 * once. A deadline that has already passed still returns this plan, with {@code deadlineExhausted}
 * set, because sending trips to backup is the safe exit.
 */
public final class BackupFleetSolver implements FleetSolver, ReplanSolver {

  private static final String BACKUP_EXPLANATION = "baseline solver sent the trip to backup";

  @Override
  public SolveResult solve(PlannerInput input, SolverConfig config, Clock clock) {
    Objects.requireNonNull(input, "input must not be null");
    Objects.requireNonNull(config, "config must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
    return finish(input, config, clock, 0L);
  }

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
    return finish(input, config, clock, changedAssignments(current, input));
  }

  private static SolveResult finish(
      PlannerInput input, SolverConfig config, Clock clock, long changedAssignments) {
    boolean deadlineExhausted = !clock.instant().isBefore(config.deadline());
    FleetPlan plan = backupPlan(input, config);
    ObjectiveScore score =
        new ObjectiveScore(
            input.trips().size(), changedAssignments, plan.totalChargingCostMicros());
    return new SolveResult(plan, score, deadlineExhausted);
  }

  private static FleetPlan backupPlan(PlannerInput input, SolverConfig config) {
    List<VehicleSchedule> schedules = new ArrayList<>();
    for (CabShift cab : input.cabs()) {
      schedules.add(
          new VehicleSchedule(
              cab.vehicle(),
              cab.startLocation(),
              cab.initialSocWh(),
              cab.availableFrom(),
              List.of()));
    }
    List<UnassignedTrip> unassigned = new ArrayList<>();
    for (Trip trip : input.trips()) {
      unassigned.add(
          UnassignedTrip.of(trip, UnassignedReasonCode.SAFE_PATCH_FALLBACK, BACKUP_EXPLANATION));
    }
    return new FleetPlan(
        "backup-" + input.inputHash(),
        input.tenantId(),
        input.horizonStart(),
        input.horizonEnd(),
        schedules,
        unassigned,
        config.seed(),
        input.inputHash());
  }

  private static long changedAssignments(FleetPlan current, PlannerInput input) {
    Set<String> assigned = new HashSet<>();
    for (VehicleSchedule schedule : current.vehicleSchedules()) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof TripAssignment assignment) {
          assigned.add(assignment.trip().id().value());
        }
      }
    }
    long changed = 0L;
    for (Trip trip : input.trips()) {
      if (assigned.contains(trip.id().value())) {
        changed++;
      }
    }
    return changed;
  }
}
