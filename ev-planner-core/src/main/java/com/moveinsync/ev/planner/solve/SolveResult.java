package com.moveinsync.ev.planner.solve;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.planner.objective.ObjectiveScore;
import java.util.Objects;

/**
 * A finished solver run. When {@code deadlineExhausted} is true the plan is still safe to hand to
 * the validator: coverage may have been given up, the hard rules have not.
 *
 * @param plan candidate plan, including its seed and input hash
 * @param score objective of {@code plan}
 * @param deadlineExhausted true when the clock was already at or past the deadline
 */
public record SolveResult(FleetPlan plan, ObjectiveScore score, boolean deadlineExhausted) {

  public SolveResult {
    Objects.requireNonNull(plan, "plan must not be null");
    Objects.requireNonNull(score, "score must not be null");
  }
}
