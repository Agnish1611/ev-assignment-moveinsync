package com.moveinsync.ev.planner.operator;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Clock;
import java.util.SplittableRandom;

/**
 * Lifts trips out of a plan so a {@link RepairOperator} can place them again. Implementations poll
 * the deadline and document their time and space cost.
 */
public interface DestroyOperator {

  /**
   * Removes zero or more trips.
   *
   * @param input ground truth
   * @param plan plan to edit
   * @param config deadline and seed
   * @param random seeded randomness for this call
   * @param clock source of "now"
   * @return the surviving plan and the lifted trip ids
   */
  DestroyedPlan destroy(
      PlannerInput input,
      FleetPlan plan,
      SolverConfig config,
      SplittableRandom random,
      Clock clock);
}
