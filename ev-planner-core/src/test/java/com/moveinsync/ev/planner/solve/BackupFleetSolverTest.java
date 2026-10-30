package com.moveinsync.ev.planner.solve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.operator.DestroyedPlan;
import com.moveinsync.ev.planner.operator.IdentityRepairOperator;
import com.moveinsync.ev.planner.operator.RetainAllDestroyOperator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackupFleetSolverTest {

  private final BackupFleetSolver solver = new BackupFleetSolver();
  private final PlannerInput input = PlannerFixtures.sample();
  private final SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 42L);
  private final Clock early = Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC);

  @Test
  @DisplayName("Every trip goes to backup and a second run is identical")
  void solveSendsEveryTripToBackup() {
    SolveResult first = solver.solve(input, config, early);
    SolveResult second = solver.solve(input, config, early);

    assertThat(first.deadlineExhausted()).isFalse();
    assertThat(first.plan().totalAssignedTrips()).isZero();
    assertThat(first.plan().totalUnassignedTrips()).isEqualTo(input.trips().size());
    assertThat(first.plan().vehicleSchedules()).hasSize(input.cabs().size());
    assertThat(first.plan().vehicleSchedules()).allMatch(VehicleSchedule::isEmpty);
    assertThat(first.plan().unassignedTrips())
        .allMatch(trip -> trip.reasonCode() == UnassignedReasonCode.SAFE_PATCH_FALLBACK);
    assertThat(first.plan().seed()).isEqualTo(42L);
    assertThat(first.plan().inputHash()).isEqualTo(input.inputHash());
    assertThat(first.score().backupTrips()).isEqualTo(input.trips().size());
    assertThat(first.score().changedAssignments()).isZero();
    assertThat(first.score().chargingCostMicros()).isZero();
    assertThat(second.plan().inputHash()).isEqualTo(first.plan().inputHash());
    assertThat(second.plan().seed()).isEqualTo(first.plan().seed());
    assertThat(second.plan().unassignedTrips())
        .containsExactlyElementsOf(first.plan().unassignedTrips());
  }

  @Test
  @DisplayName("A deadline that has already passed still returns the backup plan")
  void expiredDeadlineStillReturnsAPlan() {
    Clock late = Clock.fixed(PlannerFixtures.END.plusSeconds(1), ZoneOffset.UTC);

    SolveResult result = solver.solve(input, config, late);

    assertThat(result.deadlineExhausted()).isTrue();
    assertThat(result.plan().totalAssignedTrips()).isZero();
  }

  @Test
  @DisplayName("A replan counts assignments that the backup plan drops")
  void replanCountsDroppedAssignments() {
    TripAssignment assignment =
        new TripAssignment(
            input.trips().get(0),
            input.cabs().get(0).vehicle().id(),
            input.trips().get(0).startTs(),
            input.trips().get(0).plannedDropoffTs(),
            0,
            0,
            0L,
            40_000L,
            30_000L);
    VehicleSchedule schedule =
        new VehicleSchedule(
            input.cabs().get(0).vehicle(),
            input.cabs().get(0).startLocation(),
            input.cabs().get(0).initialSocWh(),
            input.cabs().get(0).availableFrom(),
            List.of(assignment));
    FleetPlan current =
        new FleetPlan(
            "current",
            input.tenantId(),
            input.horizonStart(),
            input.horizonEnd(),
            List.of(schedule),
            List.of(),
            1L,
            input.inputHash());
    DriftEvent event = new DriftEvent(DriftEvent.Kind.BATTERY, "CAB-1", PlannerFixtures.START);

    SolveResult result = solver.replan(input, current, event, config, early);

    assertThat(result.score().changedAssignments()).isEqualTo(1L);
    assertThat(result.plan().totalAssignedTrips()).isZero();

    FleetPlan otherTenant =
        new FleetPlan(
            "other",
            TenantId.of("tenant-b"),
            input.horizonStart(),
            input.horizonEnd(),
            List.of(),
            List.of(),
            1L,
            "hash");
    assertThatThrownBy(() -> solver.replan(input, otherTenant, event, config, early))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenant");
  }

  @Test
  @DisplayName("Identity operators leave the backup plan unchanged")
  void identityOperatorsDoNotAssignTrips() {
    FleetPlan plan = solver.solve(input, config, early).plan();
    SplittableRandom random = new SplittableRandom(config.seed());
    DestroyedPlan destroyed =
        new RetainAllDestroyOperator().destroy(input, plan, config, random, early);

    assertThat(destroyed.removedTripIds()).isEmpty();
    Clock late = Clock.fixed(PlannerFixtures.END, ZoneOffset.UTC);
    assertThat(new RetainAllDestroyOperator().destroy(input, plan, config, random, late).kept())
        .isSameAs(plan);
    assertThat(new IdentityRepairOperator().repair(input, destroyed, config, random, late))
        .isSameAs(plan);
    assertThat(new IdentityRepairOperator().repair(input, destroyed, config, random, early))
        .isSameAs(plan);
    assertThat(new DestroyedPlan(plan, List.of(input.trips().get(0).id())).removedTripIds())
        .containsExactly(input.trips().get(0).id());
    assertThatThrownBy(() -> new DriftEvent(DriftEvent.Kind.LATE_CAB, "a@b", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new RetainAllDestroyOperator().destroy(input, plan, config, random, null))
        .isInstanceOf(NullPointerException.class);
  }
}
