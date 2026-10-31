package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.operator.DestroyedPlan;
import com.moveinsync.ev.planner.operator.RepairOperator;
import java.time.Clock;
import java.util.SplittableRandom;

/**
 * Inserts lifted trips by regret-2 or regret-3. A trip with fewer feasible cabs than the depth is
 * inserted before a trip that has a full set of alternatives.
 *
 * <p>Time and space follow {@link RegretInserter}. A deadline that has already passed returns the
 * surviving plan unchanged.
 */
public final class RegretRepairOperator implements RepairOperator {

  private final int regretDepth;

  public RegretRepairOperator(int regretDepth) {
    if (regretDepth != 2 && regretDepth != 3) {
      throw new IllegalArgumentException("regret depth must be 2 or 3: " + regretDepth);
    }
    this.regretDepth = regretDepth;
  }

  @Override
  public FleetPlan repair(
      PlannerInput input,
      DestroyedPlan destroyed,
      SolverConfig config,
      SplittableRandom random,
      Clock clock) {
    return RepairLoop.repair(input, destroyed, config, random, clock, regretDepth);
  }
}
