package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.FlowBound;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Retimes charging after trip assignments are frozen.
 *
 * <p>The preemptive flow prices splittable slots. Only a contiguous block that stays feasible and
 * strictly cheaper replaces a session. Time O(C · A · H · P · K + J · H · N) for C cabs, A
 * activities, H hubs, P plugs, K start seeds, J sessions, and N slots. Space O(J · H · N). The
 * deadline is polled before each cab; a late clock leaves the remaining cabs unchanged.
 */
final class FlowRetimer {

  record Retimed(FleetPlan plan, FlowBound bound, boolean deadlineExhausted) {}

  static Retimed retime(PlannerInput input, FleetPlan plan, SolverConfig config, Clock clock) {
    Objects.requireNonNull(input, "input must not be null");
    Objects.requireNonNull(plan, "plan must not be null");
    Objects.requireNonNull(config, "config must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
    if (!clock.instant().isBefore(config.deadline())) {
      return unchanged(plan);
    }
    return rebuild(input, plan, config, clock);
  }

  private static Retimed unchanged(FleetPlan plan) {
    long cost = plan.totalChargingCostMicros();
    return new Retimed(plan, new FlowBound(cost, cost), true);
  }

  private static Retimed rebuild(
      PlannerInput input, FleetPlan plan, SolverConfig config, Clock clock) {
    List<VehicleSchedule> schedules = new ArrayList<>();
    List<ChargingSession> committed = new ArrayList<>();
    List<FlowJobs.FlowJob> jobs = new ArrayList<>();
    boolean exhausted = false;
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      if (!clock.instant().isBefore(config.deadline())) {
        exhausted = true;
        schedules.add(schedule);
        committed.addAll(sessionsIn(schedule));
        continue;
      }
      apply(input, config, schedule, committed, schedules, jobs);
    }
    FleetPlan rebuilt = copy(plan, schedules);
    long achieved = rebuilt.totalChargingCostMicros();
    long relaxation = PreemptiveFlow.micros(input, jobs);
    return new Retimed(rebuilt, new FlowBound(relaxation, achieved), exhausted);
  }

  private static void apply(
      PlannerInput input,
      SolverConfig config,
      VehicleSchedule schedule,
      List<ChargingSession> committed,
      List<VehicleSchedule> schedules,
      List<FlowJobs.FlowJob> jobs) {
    PlugCalendar plugs = booked(committed);
    Optional<RouteRetimer.Draft> draft =
        RouteRetimer.retime(
            shiftFor(input, schedule), schedule, input, config.planningFloorPercent(), plugs);
    VehicleSchedule chosen = schedule;
    if (draft.isPresent()) {
      chosen = draft.orElseThrow().schedule();
      jobs.addAll(draft.orElseThrow().jobs());
    }
    schedules.add(chosen);
    committed.addAll(sessionsIn(chosen));
  }

  private static PlugCalendar booked(List<ChargingSession> sessions) {
    PlugCalendar plugs = new PlugCalendar();
    for (ChargingSession session : sessions) {
      plugs.book(session);
    }
    return plugs;
  }

  private static CabShift shiftFor(PlannerInput input, VehicleSchedule schedule) {
    for (CabShift cab : input.cabs()) {
      if (cab.vehicle().id().equals(schedule.vehicleId())) {
        return cab;
      }
    }
    throw new IllegalArgumentException("plan cab is not in the planner input");
  }

  private static List<ChargingSession> sessionsIn(VehicleSchedule schedule) {
    List<ChargingSession> sessions = new ArrayList<>();
    for (RouteActivity activity : schedule.activities()) {
      if (activity instanceof ChargingSession session) {
        sessions.add(session);
      }
    }
    return sessions;
  }

  private static FleetPlan copy(FleetPlan plan, List<VehicleSchedule> schedules) {
    return new FleetPlan(
        plan.planId(),
        plan.tenantId(),
        plan.horizonStart(),
        plan.horizonEnd(),
        schedules,
        plan.unassignedTrips(),
        plan.seed(),
        plan.inputHash());
  }
}
