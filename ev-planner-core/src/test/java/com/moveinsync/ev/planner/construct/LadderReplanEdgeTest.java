package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.DriftEvent;
import com.moveinsync.ev.planner.solve.SolveResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Safe patch, lock window, and deadline behaviour for the replan ladder. */
class LadderReplanEdgeTest {

  private final LadderReplanSolver solver = new LadderReplanSolver();
  private final SolverConfig config =
      SolverConfig.safeBaseline(PlannerFixtures.START.plusSeconds(2), 11L);
  private final Clock clock = Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC);

  @Test
  void aChargeKeepsTheTripWhenThePickupIsLater() {
    Trip trip = ReplanScenes.trip("TRIP-LATER", 3_600, 10_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-A", 13_200L)), List.of(trip));
    FleetPlan current =
        ReplanScenes.plan(
            fleet, "plan-charge", List.of(new ReplanScenes.Hold("CAB-A", trip, 24_600L)));

    SolveResult result =
        solver.replan(
            fleet,
            current,
            new DriftEvent(DriftEvent.Kind.LATE_CAB, "CAB-A", PlannerFixtures.START),
            config,
            clock);

    assertThat(ReplanScenes.cabOf(result.plan(), "TRIP-LATER")).isEqualTo("CAB-A");
    assertThat(result.plan().totalChargingSessions()).isEqualTo(1);
    assertThat(result.score().changedAssignments()).isZero();
    assertThat(result.deadlineExhausted()).isFalse();
  }

  @Test
  void aTripWithNowhereToGoIsSentToBackup() {
    Trip trip = ReplanScenes.trip("TRIP-NEXT", 500, 10_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-A", 13_200L)), List.of(trip));
    FleetPlan current =
        ReplanScenes.plan(
            fleet, "plan-solo", List.of(new ReplanScenes.Hold("CAB-A", trip, 24_600L)));

    SolveResult result = replan(fleet, current, DriftEvent.Kind.BATTERY, "CAB-A");

    assertThat(ReplanScenes.cabOf(result.plan(), "TRIP-NEXT")).isEmpty();
    assertThat(result.plan().unassignedTrips()).hasSize(1);
    UnassignedTrip backed = result.plan().unassignedTrips().get(0);
    assertThat(backed.reasonCode()).isEqualTo(UnassignedReasonCode.SAFE_PATCH_FALLBACK);
    assertThat(backed.explanation()).doesNotContain("@");
    assertThat(result.deadlineExhausted()).isFalse();
  }

  @Test
  void aFrozenTripOnAnotherCabIsNotMoved() {
    Trip fine = ReplanScenes.trip("TRIP-FINE", 3_600, 2_000, 1_200);
    Trip locked = ReplanScenes.trip("TRIP-LOCK", 500, 10_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(
            List.of(PlannerFixtures.cab("CAB-A", 50_000L), PlannerFixtures.cab("CAB-B", 13_200L)),
            List.of(fine, locked));
    FleetPlan current =
        ReplanScenes.plan(
            fleet,
            "plan-lock",
            List.of(
                new ReplanScenes.Hold("CAB-A", fine, 50_000L),
                new ReplanScenes.Hold("CAB-B", locked, 24_600L)));

    SolveResult result = replan(fleet, current, DriftEvent.Kind.BATTERY, "CAB-A");

    assertThat(ReplanScenes.cabOf(result.plan(), "TRIP-FINE")).isEqualTo("CAB-A");
    assertThat(ReplanScenes.cabOf(result.plan(), "TRIP-LOCK")).isEmpty();
    assertThat(result.plan().unassignedTrips().get(0).reasonCode())
        .isEqualTo(UnassignedReasonCode.SAFE_PATCH_FALLBACK);
  }

  @Test
  void aChargerFailureDoesNotMoveAFeasiblePlan() {
    Trip first = ReplanScenes.trip("TRIP-1", 3_600, 2_000, 1_200);
    Trip second = ReplanScenes.trip("TRIP-2", 3_600, 2_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(
            List.of(PlannerFixtures.cab("CAB-A", 50_000L), PlannerFixtures.cab("CAB-B", 50_000L)),
            List.of(first, second));
    FleetPlan current =
        ReplanScenes.plan(
            fleet,
            "plan-ok",
            List.of(
                new ReplanScenes.Hold("CAB-A", first, 50_000L),
                new ReplanScenes.Hold("CAB-B", second, 50_000L)));

    SolveResult result = replan(fleet, current, DriftEvent.Kind.CHARGER_FAILURE, "CHG-1");

    assertThat(result.score().changedAssignments()).isZero();
    assertThat(ReplanScenes.cabOf(result.plan(), "TRIP-1")).isEqualTo("CAB-A");
    assertThat(ReplanScenes.cabOf(result.plan(), "TRIP-2")).isEqualTo("CAB-B");
  }

  @Test
  void anAlreadyUnassignedTripStaysUnassigned() {
    Trip parked = ReplanScenes.trip("TRIP-PARK", 3_600, 2_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-A", 50_000L)), List.of(parked));
    UnassignedTrip prior =
        UnassignedTrip.of(
            parked, UnassignedReasonCode.NO_CAB_WITH_ENOUGH_CHARGE, "no cab had slack");
    FleetPlan current = ReplanScenes.plan(fleet, "plan-park", List.of(), List.of(prior));

    SolveResult result = replan(fleet, current, DriftEvent.Kind.CHARGER_FAILURE, "CHG-1");

    assertThat(result.plan().totalAssignedTrips()).isZero();
    assertThat(result.plan().unassignedTrips().get(0).reasonCode())
        .isEqualTo(UnassignedReasonCode.NO_CAB_WITH_ENOUGH_CHARGE);
  }

  @Test
  void aMissedDeadlineSafePatchesWithoutKeepingTheOldSoc() {
    Trip stuck = ReplanScenes.trip("TRIP-NEXT", 500, 10_000, 1_200);
    Trip fine = ReplanScenes.trip("TRIP-FINE", 3_600, 2_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(
            List.of(PlannerFixtures.cab("CAB-A", 13_200L), PlannerFixtures.cab("CAB-B", 50_000L)),
            List.of(stuck, fine));
    FleetPlan current =
        ReplanScenes.plan(
            fleet,
            "plan-late",
            List.of(
                new ReplanScenes.Hold("CAB-A", stuck, 24_600L),
                new ReplanScenes.Hold("CAB-B", fine, 50_000L)));
    Clock late = Clock.fixed(config.deadline(), ZoneOffset.UTC);

    SolveResult result =
        solver.replan(
            fleet,
            current,
            new DriftEvent(DriftEvent.Kind.BATTERY, "CAB-A", PlannerFixtures.START),
            config,
            late);

    assertThat(result.deadlineExhausted()).isTrue();
    assertThat(ReplanScenes.cabOf(result.plan(), "TRIP-NEXT")).isEmpty();
    assertThat(ReplanScenes.cabOf(result.plan(), "TRIP-FINE")).isEqualTo("CAB-B");
  }

  @Test
  void theLadderStopsWhenTheClockPassesTheDeadlineAfterTierZero() {
    Trip big = ReplanScenes.trip("TRIP-BIG", 500, 10_000, 4_000);
    Trip block = ReplanScenes.trip("TRIP-BLOCK", 3_600, 2_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(
            List.of(PlannerFixtures.cab("CAB-A", 13_200L), PlannerFixtures.cab("CAB-B", 50_000L)),
            List.of(big, block));
    FleetPlan current =
        ReplanScenes.plan(
            fleet,
            "plan-step",
            List.of(
                new ReplanScenes.Hold("CAB-A", big, 24_600L),
                new ReplanScenes.Hold("CAB-B", block, 50_000L)));

    SolveResult result =
        solver.replan(
            fleet,
            current,
            new DriftEvent(DriftEvent.Kind.BATTERY, "CAB-A", PlannerFixtures.START),
            config,
            new SteppingClock(PlannerFixtures.START, config.deadline()));

    assertThat(result.deadlineExhausted()).isTrue();
    assertThat(ReplanScenes.cabOf(result.plan(), "TRIP-BIG")).isEmpty();
    assertThat(ReplanScenes.cabOf(result.plan(), "TRIP-BLOCK")).isEqualTo("CAB-B");
  }

  private SolveResult replan(
      PlannerInput fleet, FleetPlan current, DriftEvent.Kind kind, String subject) {
    return solver.replan(
        fleet, current, new DriftEvent(kind, subject, PlannerFixtures.START), config, clock);
  }

  private static final class SteppingClock extends Clock {
    private final Instant first;
    private final Instant rest;
    private int reads;

    private SteppingClock(Instant first, Instant rest) {
      this.first = first;
      this.rest = rest;
    }

    @Override
    public Instant instant() {
      Instant value = reads == 0 ? first : rest;
      reads++;
      return value;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
