package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.operator.DestroyedPlan;
import com.moveinsync.ev.planner.operator.RepairOperator;
import java.time.Clock;
import java.util.SplittableRandom;

/**
 * Inserts lifted trips in start-time order onto the cheapest feasible cab.
 *
 * <p>Time O(R · C · H) for R lifted trips, C cabs, and H hubs. Space O(R + C). A deadline that has
 * already passed returns the surviving plan unchanged.
 */
public final class GreedyRepairOperator implements RepairOperator {

  @Override
  public FleetPlan repair(
      PlannerInput input,
      DestroyedPlan destroyed,
      SolverConfig config,
      SplittableRandom random,
      Clock clock) {
    return RepairLoop.repair(input, destroyed, config, random, clock, 0);
  }
}
