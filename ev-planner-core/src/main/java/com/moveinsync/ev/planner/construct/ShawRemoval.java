package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.operator.DestroyOperator;
import com.moveinsync.ev.planner.operator.DestroyedPlan;
import java.time.Clock;
import java.util.SplittableRandom;

/**
 * Lifts a seed trip and the eligible trips closest to it in start time.
 *
 * <p>Time O(T · C · H) because the surviving routes are simulated again. Space O(T + C). The clock
 * is read once. Relatedness ties break by trip id.
 */
public final class ShawRemoval implements DestroyOperator {

  @Override
  public DestroyedPlan destroy(
      PlannerInput input,
      FleetPlan plan,
      SolverConfig config,
      SplittableRandom random,
      Clock clock) {
    return TripRemoval.remove(input, plan, config, random, clock, TripRemoval.Style.SHAW);
  }
}
