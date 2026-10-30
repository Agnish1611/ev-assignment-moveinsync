package com.moveinsync.ev.planner.operator;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * Destroy operator that lifts nothing.
 *
 * <p>Time O(1). Space O(1). It polls the clock so a broken clock fails before a later operator
 * runs. The returned plan is the same object that was passed in.
 */
public final class RetainAllDestroyOperator implements DestroyOperator {

  @Override
  public DestroyedPlan destroy(
      PlannerInput input,
      FleetPlan plan,
      SolverConfig config,
      SplittableRandom random,
      Clock clock) {
    Objects.requireNonNull(input, "input must not be null");
    Objects.requireNonNull(plan, "plan must not be null");
    Objects.requireNonNull(config, "config must not be null");
    Objects.requireNonNull(random, "random must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
    if (!clock.instant().isBefore(config.deadline())) {
      return new DestroyedPlan(plan, List.of());
    }
    return new DestroyedPlan(plan, List.of());
  }
}
