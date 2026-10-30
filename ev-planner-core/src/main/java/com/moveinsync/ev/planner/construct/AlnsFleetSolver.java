package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.objective.ObjectiveScore;
import com.moveinsync.ev.planner.operator.DestroyOperator;
import com.moveinsync.ev.planner.operator.DestroyedPlan;
import com.moveinsync.ev.planner.operator.RepairOperator;
import com.moveinsync.ev.planner.solve.BackupFleetSolver;
import com.moveinsync.ev.planner.solve.FleetSolver;
import com.moveinsync.ev.planner.solve.SolveResult;
import java.time.Clock;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * Adaptive large-neighbourhood search over the construction plan.
 *
 * <p>Each iteration destroys with random, worst-cost, or Shaw removal and repairs with greedy,
 * regret-2, or regret-3. Operator choice is a weighted draw. A worse score is accepted when a
 * seeded draw falls under a cooling threshold that starts at {@value #INITIAL_TEMPERATURE} out of
 * {@value #TEMPERATURE_SCALE} and is multiplied by 9/10 each iteration. The best score seen is
 * returned. Time O(I · T^2 · C · H) for I iterations, T trips, C cabs, and H hubs. Space O(T + C).
 * The deadline is polled before construction and before every iteration. {@code maxIterations} of 0
 * returns construction unchanged.
 */
public final class AlnsFleetSolver implements FleetSolver {

  static final int INITIAL_TEMPERATURE = 2_000;
  static final int TEMPERATURE_SCALE = 10_000;
  private static final int WEIGHT_CAP = 100;
  private static final int START_WEIGHT = 10;

  @Override
  public SolveResult solve(PlannerInput input, SolverConfig config, Clock clock) {
    Objects.requireNonNull(input, "input must not be null");
    Objects.requireNonNull(config, "config must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
    if (!clock.instant().isBefore(config.deadline())) {
      Clock late = Clock.fixed(config.deadline(), clock.getZone());
      return new BackupFleetSolver().solve(input, config, late);
    }
    SolveResult initial = new GreedyConstructionSolver().solve(input, config, clock);
    if (config.maxIterations() == 0 || initial.deadlineExhausted()) {
      return initial;
    }
    return search(input, config, clock, initial);
  }

  private static SolveResult search(
      PlannerInput input, SolverConfig config, Clock clock, SolveResult initial) {
    DestroyOperator[] destroyers = {
      new RandomTripRemoval(), new WorstCostRemoval(), new ShawRemoval()
    };
    RepairOperator[] repairers = {
      new GreedyRepairOperator(), new RegretRepairOperator(2), new RegretRepairOperator(3)
    };
    int[] destroyWeights = {START_WEIGHT, START_WEIGHT, START_WEIGHT};
    int[] repairWeights = {START_WEIGHT, START_WEIGHT, START_WEIGHT};
    SplittableRandom random = new SplittableRandom(config.seed());
    FleetPlan current = initial.plan();
    FleetPlan best = current;
    ObjectiveScore currentScore = initial.score();
    ObjectiveScore bestScore = currentScore;
    int temperature = INITIAL_TEMPERATURE;
    for (int iteration = 0; iteration < config.maxIterations(); iteration++) {
      if (!clock.instant().isBefore(config.deadline())) {
        return new SolveResult(rename(best), bestScore, true);
      }
      int destroyIndex = roulette(destroyWeights, random);
      int repairIndex = roulette(repairWeights, random);
      DestroyedPlan destroyed =
          destroyers[destroyIndex].destroy(input, current, config, random, clock);
      FleetPlan candidate = repairers[repairIndex].repair(input, destroyed, config, random, clock);
      ObjectiveScore candidateScore = score(candidate);
      boolean improved =
          candidateScore.comparePreferringFewerBackups(bestScore, config.changePenaltyMicros()) < 0;
      if (improved) {
        best = candidate;
        bestScore = candidateScore;
        reward(destroyWeights, destroyIndex, 4);
        reward(repairWeights, repairIndex, 4);
      }
      if (accept(candidateScore, currentScore, config, random, temperature)) {
        current = candidate;
        currentScore = candidateScore;
        if (!improved) {
          reward(destroyWeights, destroyIndex, 1);
          reward(repairWeights, repairIndex, 1);
        }
      }
      temperature = temperature * 9 / 10;
    }
    return new SolveResult(rename(best), bestScore, false);
  }

  private static boolean accept(
      ObjectiveScore candidate,
      ObjectiveScore current,
      SolverConfig config,
      SplittableRandom random,
      int temperature) {
    if (candidate.comparePreferringFewerBackups(current, config.changePenaltyMicros()) <= 0) {
      return true;
    }
    if (temperature <= 0) {
      return false;
    }
    return random.nextInt(TEMPERATURE_SCALE) < temperature;
  }

  private static int roulette(int[] weights, SplittableRandom random) {
    int total = 0;
    for (int weight : weights) {
      total += weight;
    }
    int draw = random.nextInt(total);
    int cursor = 0;
    for (int index = 0; index < weights.length; index++) {
      cursor += weights[index];
      if (draw < cursor) {
        return index;
      }
    }
    return weights.length - 1;
  }

  private static void reward(int[] weights, int index, int amount) {
    weights[index] = Math.min(WEIGHT_CAP, weights[index] + amount);
  }

  private static ObjectiveScore score(FleetPlan plan) {
    return new ObjectiveScore(plan.totalUnassignedTrips(), 0L, plan.totalChargingCostMicros());
  }

  private static FleetPlan rename(FleetPlan plan) {
    return new FleetPlan(
        "alns-" + plan.inputHash(),
        plan.tenantId(),
        plan.horizonStart(),
        plan.horizonEnd(),
        plan.vehicleSchedules(),
        plan.unassignedTrips(),
        plan.seed(),
        plan.inputHash());
  }
}
