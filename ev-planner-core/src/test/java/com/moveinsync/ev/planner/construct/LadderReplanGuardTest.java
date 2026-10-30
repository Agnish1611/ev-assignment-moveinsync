package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.DriftEvent;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Rejected arguments and the tier-2 version check. */
class LadderReplanGuardTest {

  private final LadderReplanSolver solver = new LadderReplanSolver();
  private final SolverConfig config =
      SolverConfig.safeBaseline(PlannerFixtures.START.plusSeconds(2), 11L);
  private final Clock clock = Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC);

  @Test
  void polishRejectsAStalePlanIdAndKeepsABetterScore() {
    Trip trip = ReplanScenes.trip("TRIP-1", 3_600, 2_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-A", 50_000L)), List.of(trip));
    FleetPlan current = ReplanScenes.plan(fleet, "plan-id", List.of());
    UnassignedTrip row =
        UnassignedTrip.of(
            trip, UnassignedReasonCode.SAFE_PATCH_FALLBACK, LadderReplanSolver.SAFE_EXPLANATION);
    LadderReplanSolver.Attempt worse =
        new LadderReplanSolver.Attempt(List.of(), List.of(row), true);
    LadderReplanSolver.Attempt better = new LadderReplanSolver.Attempt(List.of(), List.of(), true);

    assertThat(LadderReplanSolver.polish(worse, better, "stale", current, config)).isSameAs(worse);
    assertThat(LadderReplanSolver.polish(worse, better, "plan-id", current, config))
        .isSameAs(better);
    assertThat(LadderReplanSolver.polish(better, worse, "plan-id", current, config))
        .isSameAs(better);
    assertThat(LadderReplanSolver.polish(null, better, "plan-id", current, config))
        .isSameAs(better);
  }

  @Test
  void rejectedArgumentsThrow() {
    PlannerInput fleet = PlannerFixtures.sample();
    FleetPlan current =
        new FleetPlan(
            "hand",
            fleet.tenantId(),
            fleet.horizonStart(),
            fleet.horizonEnd(),
            List.of(),
            List.of(),
            1L,
            fleet.inputHash());
    DriftEvent event = new DriftEvent(DriftEvent.Kind.BATTERY, "CAB-1", PlannerFixtures.START);
    assertThatThrownBy(() -> solver.replan(null, current, event, config, clock))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> solver.replan(fleet, null, event, config, clock))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> solver.replan(fleet, current, null, config, clock))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> solver.replan(fleet, current, event, null, clock))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> solver.replan(fleet, current, event, config, null))
        .isInstanceOf(NullPointerException.class);
    FleetPlan other =
        new FleetPlan(
            "other",
            TenantId.of("tenant-b"),
            fleet.horizonStart(),
            fleet.horizonEnd(),
            List.of(),
            List.of(),
            1L,
            fleet.inputHash());
    assertThatThrownBy(() -> solver.replan(fleet, other, event, config, clock))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenant");
    DriftEvent unknown = new DriftEvent(DriftEvent.Kind.BATTERY, "CAB-Z", PlannerFixtures.START);
    assertThatThrownBy(() -> solver.replan(fleet, current, unknown, config, clock))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a cab");
  }

  @Test
  void aTripAssignedTwiceIsRejected() {
    Trip trip = ReplanScenes.trip("TRIP-1", 3_600, 2_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(
            List.of(PlannerFixtures.cab("CAB-A", 50_000L), PlannerFixtures.cab("CAB-B", 50_000L)),
            List.of(trip));
    FleetPlan current =
        ReplanScenes.plan(
            fleet,
            "plan-dup",
            List.of(
                new ReplanScenes.Hold("CAB-A", trip, 50_000L),
                new ReplanScenes.Hold("CAB-B", trip, 50_000L)));

    assertThatThrownBy(
            () ->
                solver.replan(
                    fleet,
                    current,
                    new DriftEvent(DriftEvent.Kind.CHARGER_FAILURE, "CHG-1", PlannerFixtures.START),
                    config,
                    clock))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("more than once");
  }
}
