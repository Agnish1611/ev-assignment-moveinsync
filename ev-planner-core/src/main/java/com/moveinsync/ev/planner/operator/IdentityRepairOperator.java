package com.moveinsync.ev.planner.operator;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Clock;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * Repair operator that returns the surviving plan and does not reinsert lifted trips.
 *
 * <p>Time O(1). Space O(1). Later repair operators replace this one. It exists so the operator
 * contract can be executed without assigning a trip.
 */
public final class IdentityRepairOperator implements RepairOperator {

  @Override
  public FleetPlan repair(
      PlannerInput input,
      DestroyedPlan destroyed,
      SolverConfig config,
      SplittableRandom random,
      Clock clock) {
    Objects.requireNonNull(input, "input must not be null");
    Objects.requireNonNull(destroyed, "destroyed must not be null");
    Objects.requireNonNull(config, "config must not be null");
    Objects.requireNonNull(random, "random must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
    if (!clock.instant().isBefore(config.deadline())) {
      return destroyed.kept();
    }
    return destroyed.kept();
  }
}
