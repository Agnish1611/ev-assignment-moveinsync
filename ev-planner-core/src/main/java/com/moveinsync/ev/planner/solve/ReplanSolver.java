package com.moveinsync.ev.planner.solve;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Clock;

/**
 * Repairs an existing plan after a drift event. The same deadline rule as {@link FleetSolver}
 * applies. A missed deadline still returns a plan the validator can accept.
 */
public interface ReplanSolver {

  /**
   * Repairs {@code current} after {@code event}.
   *
   * @param input ground truth for the shift
   * @param current the plan being repaired
   * @param event why the repair was requested
   * @param config deadline, seed, and weights
   * @param clock source of "now"
   * @return a plan, its score, and whether the deadline had already passed
   */
  SolveResult replan(
      PlannerInput input, FleetPlan current, DriftEvent event, SolverConfig config, Clock clock);
}
