package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.DriftEvent;
import com.moveinsync.ev.planner.solve.SolveResult;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The 22 percent reading, a one-neighbour swap, and a two-neighbour repair. */
class LadderReplanTest {

  private final LadderReplanSolver solver = new LadderReplanSolver();
  private final SolverConfig config =
      SolverConfig.safeBaseline(PlannerFixtures.START.plusSeconds(2), 11L);
  private final Clock clock = Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC);

  @Test
  void aTwentyTwoPercentReadingMovesOnlyTheNextTrip() {
    Trip next = ReplanScenes.trip("TRIP-NEXT", 500, 10_000, 1_200);
    Trip locked = ReplanScenes.trip("TRIP-LOCKED", 500, 10_000, 1_200);
    Trip later = ReplanScenes.trip("TRIP-LATER", 7_200, 10_000, 1_200);
    PlannerInput drifted =
        ReplanScenes.fleet(
            List.of(
                PlannerFixtures.cab("CAB-A", 13_200L),
                PlannerFixtures.cab("CAB-B", 50_000L),
                PlannerFixtures.cab("CAB-C", 50_000L)),
            List.of(next, locked, later));
    FleetPlan current =
        ReplanScenes.plan(
            drifted,
            "plan-41",
            List.of(
                new ReplanScenes.Hold("CAB-A", next, 24_600L),
                new ReplanScenes.Hold("CAB-B", locked, 50_000L),
                new ReplanScenes.Hold("CAB-C", later, 50_000L)));
    DriftEvent event = new DriftEvent(DriftEvent.Kind.BATTERY, "CAB-A", PlannerFixtures.START);

    SolveResult first = solver.replan(drifted, current, event, config, clock);
    SolveResult second = solver.replan(drifted, current, event, config, clock);

    assertThat(ReplanScenes.cabOf(first.plan(), "TRIP-NEXT")).isEqualTo("CAB-C");
    assertThat(ReplanScenes.cabOf(first.plan(), "TRIP-LOCKED")).isEqualTo("CAB-B");
    assertThat(ReplanScenes.cabOf(first.plan(), "TRIP-LATER")).isEqualTo("CAB-C");
    assertThat(first.score().changedAssignments()).isEqualTo(1);
    assertThat(first.deadlineExhausted()).isFalse();
    assertThat(first.plan().planId()).startsWith("replan-");
    assertThat(first.plan().totalUnassignedTrips()).isZero();
    assertThat(first.flow().relaxationMicros()).isLessThanOrEqualTo(first.flow().achievedMicros());
    assertThat(AssignmentDiff.vehicles(first.plan()))
        .isEqualTo(AssignmentDiff.vehicles(second.plan()));
  }

  @Test
  void oneNeighbourFreesTheOnlyCabThatCanTakeTheTrip() {
    Trip big = ReplanScenes.trip("TRIP-BIG", 500, 10_000, 4_000);
    Trip block = ReplanScenes.trip("TRIP-BLOCK", 3_600, 2_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(
            List.of(PlannerFixtures.cab("CAB-A", 13_200L), PlannerFixtures.cab("CAB-B", 50_000L)),
            List.of(big, block));
    FleetPlan current =
        ReplanScenes.plan(
            fleet,
            "plan-swap",
            List.of(
                new ReplanScenes.Hold("CAB-A", big, 24_600L),
                new ReplanScenes.Hold("CAB-B", block, 50_000L)));

    SolveResult result =
        solver.replan(
            fleet,
            current,
            new DriftEvent(DriftEvent.Kind.BATTERY, "CAB-A", PlannerFixtures.START),
            config,
            clock);

    assertThat(ReplanScenes.cabOf(result.plan(), "TRIP-BIG")).isEqualTo("CAB-B");
    assertThat(ReplanScenes.cabOf(result.plan(), "TRIP-BLOCK")).isEqualTo("CAB-A");
    assertThat(result.score().changedAssignments()).isEqualTo(2);
    assertThat(result.plan().totalUnassignedTrips()).isZero();
    assertThat(result.deadlineExhausted()).isFalse();
  }

  @Test
  void twoNeighboursAreLiftedWhenTheClosestCabCannotTakeTheTrip() {
    Trip big = ReplanScenes.trip("TRIP-BIG", 500, 10_000, 4_000);
    Trip near = ReplanScenes.trip("TRIP-B", 3_600, 2_000, 1_200);
    Trip far = ReplanScenes.trip("TRIP-C", 3_600, 2_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(
            List.of(
                PlannerFixtures.cab("CAB-A", 13_200L),
                PlannerFixtures.cab("CAB-B", 13_200L),
                PlannerFixtures.cab("CAB-C", 50_000L)),
            List.of(big, near, far));
    FleetPlan current =
        ReplanScenes.plan(
            fleet,
            "plan-ring",
            List.of(
                new ReplanScenes.Hold("CAB-A", big, 24_600L),
                new ReplanScenes.Hold("CAB-B", near, 13_200L),
                new ReplanScenes.Hold("CAB-C", far, 50_000L)));

    SolveResult result =
        solver.replan(
            fleet,
            current,
            new DriftEvent(DriftEvent.Kind.BATTERY, "CAB-A", PlannerFixtures.START),
            config,
            clock);

    assertThat(ReplanScenes.cabOf(result.plan(), "TRIP-BIG")).isEqualTo("CAB-C");
    assertThat(result.plan().totalAssignedTrips()).isEqualTo(3);
    assertThat(result.score().changedAssignments()).isLessThanOrEqualTo(3);
    assertThat(result.plan().totalUnassignedTrips()).isZero();
    assertThat(result.deadlineExhausted()).isFalse();
  }
}
