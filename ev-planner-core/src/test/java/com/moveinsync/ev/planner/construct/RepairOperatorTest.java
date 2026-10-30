package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.operator.DestroyedPlan;
import com.moveinsync.ev.planner.solve.BackupFleetSolver;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class RepairOperatorTest {

  private final SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 7L);
  private final Clock early = Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC);
  private final SplittableRandom random = new SplittableRandom(4L);

  @Test
  void regretRepairPutsTheLiftedTripBack() {
    PlannerInput fleet = PlannerFixtures.sample();
    FleetPlan plan = solved(fleet);
    DestroyedPlan destroyed = new WorstCostRemoval().destroy(fleet, plan, config, random, early);

    FleetPlan regret2 = new RegretRepairOperator(2).repair(fleet, destroyed, config, random, early);
    FleetPlan regret3 = new RegretRepairOperator(3).repair(fleet, destroyed, config, random, early);

    assertThat(regret2.totalAssignedTrips()).isEqualTo(2);
    assertThat(regret3.totalAssignedTrips()).isEqualTo(2);
  }

  @Test
  void greedyRepairSkipsAnImpossibleTripAndInsertsTheNext() {
    Trip tooSoon = PlannerFixtures.trip("TRIP-A", PlannerFixtures.START.plusSeconds(10));
    Trip later = PlannerFixtures.trip("TRIP-B", PlannerFixtures.START.plusSeconds(600));
    PlannerInput fleet =
        PlannerFixtures.shell()
            .addCab(PlannerFixtures.cab("CAB-1", 50_000L))
            .addTrip(tooSoon)
            .addTrip(later)
            .build();
    FleetPlan kept = new BackupFleetSolver().solve(fleet, config, early).plan();
    DestroyedPlan destroyed = new DestroyedPlan(kept, List.of(tooSoon.id(), later.id()));

    FleetPlan repaired = new GreedyRepairOperator().repair(fleet, destroyed, config, random, early);

    assertThat(PlanViews.byTrip(repaired)).containsOnlyKeys("TRIP-B");
    assertThat(repaired.unassignedTrips().get(0).reasonCode())
        .isEqualTo(UnassignedReasonCode.NO_CAB_FREE_IN_TIME_WINDOW);
  }

  @Test
  void aPassedDeadlineReturnsTheSurvivingPlan() {
    FleetPlan plan = solved(PlannerFixtures.sample());
    DestroyedPlan destroyed = new DestroyedPlan(plan, List.of());
    Clock late = Clock.fixed(PlannerFixtures.END, ZoneOffset.UTC);

    assertThat(
            new GreedyRepairOperator()
                .repair(PlannerFixtures.sample(), destroyed, config, random, late))
        .isSameAs(plan);
  }

  @Test
  void anInfeasibleSurvivingPlanIsRejected() {
    Trip trip = PlannerFixtures.trip("TRIP-T", PlannerFixtures.START.plusSeconds(10));
    PlannerInput fleet =
        PlannerFixtures.shell().addCab(PlannerFixtures.cab("CAB-1", 50_000L)).addTrip(trip).build();
    TripAssignment assignment =
        new TripAssignment(
            trip, VehicleId.of("CAB-1"), trip.startTs(), trip.plannedDropoffTs(), 0, 0, 0L, 1L, 1L);
    FleetPlan plan = PlanViews.withActivities(fleet, List.of(assignment));

    assertThatThrownBy(
            () ->
                new GreedyRepairOperator()
                    .repair(fleet, new DestroyedPlan(plan, List.of()), config, random, early))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not feasible");
  }

  @Test
  void duplicateAndUnknownRemovedIdsAreRejected() {
    PlannerInput fleet = PlannerFixtures.sample();
    FleetPlan kept = new BackupFleetSolver().solve(fleet, config, early).plan();
    TripId known = fleet.trips().get(0).id();

    assertThatThrownBy(
            () ->
                new RegretRepairOperator(2)
                    .repair(
                        fleet,
                        new DestroyedPlan(kept, List.of(known, known)),
                        config,
                        random,
                        early))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");
    assertThatThrownBy(
            () ->
                new RegretRepairOperator(2)
                    .repair(
                        fleet,
                        new DestroyedPlan(kept, List.of(TripId.of("MISSING"))),
                        config,
                        random,
                        early))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not in the planner input");
  }

  @Test
  void regretDepthMustBeTwoOrThree() {
    assertThatThrownBy(() -> new RegretRepairOperator(1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("2 or 3");
  }

  @Test
  void nullArgumentsAreRejected() {
    FleetPlan plan = solved(PlannerFixtures.sample());
    DestroyedPlan destroyed = new DestroyedPlan(plan, List.of());

    assertThatThrownBy(
            () -> new GreedyRepairOperator().repair(null, destroyed, config, random, early))
        .isInstanceOf(NullPointerException.class);
  }

  private static FleetPlan solved(PlannerInput fleet) {
    return new GreedyConstructionSolver()
        .solve(
            fleet,
            SolverConfig.safeBaseline(PlannerFixtures.END, 7L),
            Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC))
        .plan();
  }
}
