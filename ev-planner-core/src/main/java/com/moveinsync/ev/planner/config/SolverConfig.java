package com.moveinsync.ev.planner.config;

import com.moveinsync.ev.domain.vehicle.Vehicle;
import java.time.Instant;
import java.util.Objects;

/**
 * Deadline and weights for one solver run.
 *
 * <p>The planning floor is only 17 or 18 percent (D14, D18). The validator still enforces 15
 * percent. The lock window is 900 seconds. One changed assignment costs {@value
 * #DEFAULT_CHANGE_PENALTY_MICROS} micro-units in the replan objective.
 *
 * @param deadline instant at which the solver must already have stopped, UTC
 * @param seed reproducible random seed stored on the plan
 * @param planningFloorPercent effective battery floor, 17 or 18
 * @param lockWindowSeconds trips starting inside this window stay frozen on a replan
 * @param changePenaltyMicros micro-units added to the cost term per changed assignment
 * @param maxIterations upper bound on search iterations; the baseline solver uses zero
 */
public record SolverConfig(
    Instant deadline,
    long seed,
    int planningFloorPercent,
    int lockWindowSeconds,
    long changePenaltyMicros,
    int maxIterations) {

  public static final int MIN_PLANNING_FLOOR_PERCENT = 17;
  public static final int MAX_PLANNING_FLOOR_PERCENT = 18;
  public static final int DEFAULT_LOCK_WINDOW_SECONDS = 900;
  public static final long DEFAULT_CHANGE_PENALTY_MICROS = 1_000_000L;
  public static final int BASELINE_MAX_ITERATIONS = 0;

  public SolverConfig {
    Objects.requireNonNull(deadline, "deadline must not be null");
    if (planningFloorPercent < MIN_PLANNING_FLOOR_PERCENT
        || planningFloorPercent > MAX_PLANNING_FLOOR_PERCENT) {
      throw new IllegalArgumentException(
          "planningFloorPercent must be "
              + MIN_PLANNING_FLOOR_PERCENT
              + " or "
              + MAX_PLANNING_FLOOR_PERCENT
              + ": "
              + planningFloorPercent);
    }
    if (lockWindowSeconds <= 0) {
      throw new IllegalArgumentException(
          "lockWindowSeconds must be positive: " + lockWindowSeconds);
    }
    if (changePenaltyMicros < 0) {
      throw new IllegalArgumentException(
          "changePenaltyMicros must be non-negative: " + changePenaltyMicros);
    }
    if (maxIterations < 0) {
      throw new IllegalArgumentException("maxIterations must be non-negative: " + maxIterations);
    }
  }

  /** Baseline config: 18 percent floor, 15 minute lock, no search iterations. */
  public static SolverConfig safeBaseline(Instant deadline, long seed) {
    return new SolverConfig(
        deadline,
        seed,
        Vehicle.DEFAULT_ROBUST_MARGIN_PERCENT,
        DEFAULT_LOCK_WINDOW_SECONDS,
        DEFAULT_CHANGE_PENALTY_MICROS,
        BASELINE_MAX_ITERATIONS);
  }

  /** Same floor, lock, and penalty as the baseline, with a positive search cap. */
  public SolverConfig withIterations(int maxIterations) {
    if (maxIterations <= 0) {
      throw new IllegalArgumentException("maxIterations must be positive: " + maxIterations);
    }
    return new SolverConfig(
        deadline,
        seed,
        planningFloorPercent,
        lockWindowSeconds,
        changePenaltyMicros,
        maxIterations);
  }
}
