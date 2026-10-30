package com.moveinsync.ev.planner.solve;

import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Clock;

/**
 * Builds a fresh fleet plan. Implementations poll {@code clock} against {@code config.deadline()}
 * and stop with a safe plan rather than running past the deadline. Each implementation documents
 * its time and space cost.
 */
public interface FleetSolver {

  /**
   * Solves one horizon.
   *
   * @param input ground truth for the shift
   * @param config deadline, seed, and weights
   * @param clock source of "now"; never {@code Instant.now()} inside the solver
   * @return a plan, its score, and whether the deadline had already passed
   */
  SolveResult solve(PlannerInput input, SolverConfig config, Clock clock);
}
