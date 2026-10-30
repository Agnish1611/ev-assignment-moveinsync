package com.moveinsync.ev.planner.operator;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Clock;
import java.util.SplittableRandom;

/**
 * Puts lifted trips back onto cabs, or sends them to backup when no feasible insertion remains.
 * Implementations poll the deadline and document their time and space cost.
 */
public interface RepairOperator {

  /**
   * Rebuilds a plan from a destroyed one.
   *
   * @param input ground truth
   * @param destroyed surviving plan plus lifted trip ids
   * @param config deadline and weights
   * @param random seeded randomness for this call
   * @param clock source of "now"
   * @return the repaired plan
   */
  FleetPlan repair(
      PlannerInput input,
      DestroyedPlan destroyed,
      SolverConfig config,
      SplittableRandom random,
      Clock clock);
}
