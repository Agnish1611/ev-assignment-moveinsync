package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.DriftEvent;
import com.moveinsync.ev.planner.solve.ReplanSolver;
import com.moveinsync.ev.planner.solve.SolveResult;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies several drift events to one plan. The latest event for each kind and subject is kept.
 * Equal timestamps run cab removal and charger failure before battery drift and trip edits. Once
 * the deadline has passed, the remaining events are left and the last plan is kept.
 *
 * <p>Time O(E · T^2 · C · H · P) for E events that still fit before the deadline, T trips, C cabs,
 * H hubs, and P plugs. Space O(E + T).
 */
public final class ReplanBatch {

  private ReplanBatch() {
    throw new IllegalStateException("utility");
  }

  public static SolveResult apply(
      ReplanSolver solver,
      PlannerInput input,
      FleetPlan current,
      List<DriftEvent> events,
      SolverConfig config,
      Clock clock) {
    Objects.requireNonNull(solver, "solver must not be null");
    Objects.requireNonNull(events, "events must not be null");
    if (events.isEmpty()) {
      throw new IllegalArgumentException("events must not be empty");
    }
    return climb(solver, input, current, coalesce(events), config, clock);
  }

  private static SolveResult climb(
      ReplanSolver solver,
      PlannerInput input,
      FleetPlan current,
      List<DriftEvent> unique,
      SolverConfig config,
      Clock clock) {
    SolveResult last = solver.replan(input, current, unique.get(0), config, clock);
    for (int index = 1; index < unique.size(); index++) {
      if (!clock.instant().isBefore(config.deadline())) {
        return stopped(last);
      }
      last = solver.replan(input, last.plan(), unique.get(index), config, clock);
    }
    return last;
  }

  private static SolveResult stopped(SolveResult last) {
    if (last.deadlineExhausted()) {
      return last;
    }
    return new SolveResult(last.plan(), last.score(), true, last.flow());
  }

  private static List<DriftEvent> coalesce(List<DriftEvent> events) {
    for (DriftEvent event : events) {
      Objects.requireNonNull(event, "event must not be null");
    }
    List<DriftEvent> ordered = new ArrayList<>(events);
    ordered.sort(order());
    Map<String, DriftEvent> latest = new HashMap<>();
    for (DriftEvent event : ordered) {
      String key = event.kind().name() + '\n' + event.subjectId();
      DriftEvent prior = latest.get(key);
      if (prior == null || event.observedAt().isAfter(prior.observedAt())) {
        latest.put(key, event);
      }
    }
    List<DriftEvent> unique = new ArrayList<>(latest.values());
    unique.sort(order());
    return unique;
  }

  private static Comparator<DriftEvent> order() {
    return Comparator.comparing(DriftEvent::observedAt)
        .thenComparingInt(event -> -priority(event.kind()));
  }

  private static int priority(DriftEvent.Kind kind) {
    return switch (kind) {
      case TRIP_ADDED -> 1;
      case TRIP_CANCELLED -> 2;
      case LATE_CAB -> 3;
      case BATTERY -> 4;
      case CHARGER_FAILURE -> 5;
      case CAB_REMOVED -> 6;
    };
  }
}
