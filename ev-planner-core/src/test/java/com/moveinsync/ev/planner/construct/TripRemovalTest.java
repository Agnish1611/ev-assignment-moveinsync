package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.operator.DestroyedPlan;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class TripRemovalTest {

  private final SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 7L);
  private final Clock early = Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC);
  private final Clock open =
      Clock.fixed(PlannerFixtures.START.minusSeconds(10_000), ZoneOffset.UTC);
  private final SplittableRandom random = new SplittableRandom(7L);

  @Test
  void theLockWindowKeepsTheImminentTrip() {
    FleetPlan plan = solved(PlannerFixtures.sample());

    DestroyedPlan destroyed =
        new RandomTripRemoval().destroy(PlannerFixtures.sample(), plan, config, random, early);

    assertThat(destroyed.removedTripIds()).extracting(TripId::value).containsExactly("TRIP-2");
    assertThat(PlanViews.byTrip(destroyed.kept())).containsOnlyKeys("TRIP-1");
    DestroyedPlan worst =
        new WorstCostRemoval().destroy(PlannerFixtures.sample(), plan, config, random, early);
    DestroyedPlan shaw =
        new ShawRemoval().destroy(PlannerFixtures.sample(), plan, config, random, early);
    assertThat(worst.removedTripIds()).extracting(TripId::value).containsExactly("TRIP-2");
    assertThat(shaw.removedTripIds()).extracting(TripId::value).containsExactly("TRIP-2");
  }

  @Test
  void theSameSeedRemovesTheSameTrips() {
    PlannerInput fleet = PlannerFixtures.sample();
    FleetPlan plan = solved(fleet);

    DestroyedPlan first =
        new RandomTripRemoval().destroy(fleet, plan, config, new SplittableRandom(3L), open);
    DestroyedPlan second =
        new RandomTripRemoval().destroy(fleet, plan, config, new SplittableRandom(3L), open);

    assertThat(first.removedTripIds()).containsExactlyElementsOf(second.removedTripIds());
    assertThat(first.removedTripIds()).hasSize(1);
  }

  @Test
  void worstCostRemovesTheHigherEnergyTrip() {
    PlannerInput fleet = richAndPoor();
    FleetPlan plan = solved(fleet);

    DestroyedPlan destroyed = new WorstCostRemoval().destroy(fleet, plan, config, random, open);

    assertThat(destroyed.removedTripIds()).extracting(TripId::value).containsExactly("TRIP-B");
    assertThat(PlanViews.byTrip(destroyed.kept())).containsOnlyKeys("TRIP-A");
  }

  @Test
  void shawRemovalIsDeterministic() {
    PlannerInput fleet = PlannerFixtures.sample();
    FleetPlan plan = solved(fleet);

    DestroyedPlan first =
        new ShawRemoval().destroy(fleet, plan, config, new SplittableRandom(9L), open);
    DestroyedPlan second =
        new ShawRemoval().destroy(fleet, plan, config, new SplittableRandom(9L), open);

    assertThat(first.removedTripIds()).containsExactlyElementsOf(second.removedTripIds());
  }

  @Test
  void removingAPredecessorLiftsTheTripThatDependedOnIt() {
    PlannerInput fleet = dependentChain();
    FleetPlan plan = solved(fleet);
    boolean cascaded = false;
    for (long seed = 0; seed < 30; seed++) {
      DestroyedPlan destroyed =
          new RandomTripRemoval().destroy(fleet, plan, config, new SplittableRandom(seed), open);
      if (destroyed.removedTripIds().size() >= 2) {
        cascaded = true;
      }
    }

    assertThat(cascaded).isTrue();
    assertThat(plan.totalAssignedTrips()).isEqualTo(2);
  }

  @Test
  void anInfeasibleUnfrozenTripIsLifted() {
    Trip trip = PlannerFixtures.trip("TRIP-T", PlannerFixtures.START.plusSeconds(10));
    PlannerInput fleet =
        PlannerFixtures.shell().addCab(PlannerFixtures.cab("CAB-1", 50_000L)).addTrip(trip).build();
    FleetPlan plan = PlanViews.withActivities(fleet, List.of(claimed(trip, "CAB-1")));

    DestroyedPlan destroyed = new WorstCostRemoval().destroy(fleet, plan, config, random, open);

    assertThat(destroyed.removedTripIds()).extracting(TripId::value).containsExactly("TRIP-T");
    assertThat(destroyed.kept().totalAssignedTrips()).isZero();
  }

  @Test
  void anInfeasibleFrozenTripLeavesThePlanUntouched() {
    Trip trip = PlannerFixtures.trip("TRIP-T", PlannerFixtures.START.plusSeconds(10));
    PlannerInput fleet =
        PlannerFixtures.shell().addCab(PlannerFixtures.cab("CAB-1", 50_000L)).addTrip(trip).build();
    FleetPlan plan = PlanViews.withActivities(fleet, List.of(claimed(trip, "CAB-1")));

    DestroyedPlan destroyed = new ShawRemoval().destroy(fleet, plan, config, random, early);

    assertThat(destroyed.kept()).isSameAs(plan);
    assertThat(destroyed.removedTripIds()).isEmpty();
  }

  @Test
  void aPassedDeadlineLeavesThePlanUntouched() {
    FleetPlan plan = solved(PlannerFixtures.sample());
    Clock late = Clock.fixed(PlannerFixtures.END, ZoneOffset.UTC);

    DestroyedPlan destroyed =
        new RandomTripRemoval().destroy(PlannerFixtures.sample(), plan, config, random, late);

    assertThat(destroyed.kept()).isSameAs(plan);
    assertThat(destroyed.removedTripIds()).isEmpty();
  }

  private static FleetPlan solved(PlannerInput fleet) {
    return new GreedyConstructionSolver()
        .solve(
            fleet,
            SolverConfig.safeBaseline(PlannerFixtures.END, 7L),
            Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC))
        .plan();
  }

  private static PlannerInput richAndPoor() {
    var start = PlannerFixtures.START.plusSeconds(600);
    Location from = Location.of("LOC-P", "Pickup");
    Location to = Location.of("LOC-D", "Drop");
    return PlannerFixtures.shell()
        .addCab(PlannerFixtures.cab("CAB-POOR", 16_000L, 150L, PlannerFixtures.depot()))
        .addCab(PlannerFixtures.cab("CAB-RICH", 50_000L, 100L, PlannerFixtures.depot()))
        .addTrip(PlannerFixtures.trip("TRIP-A", start, from, to, 10_000))
        .addTrip(PlannerFixtures.trip("TRIP-B", start, from, to, 40_000))
        .build();
  }

  private static PlannerInput dependentChain() {
    Location drop = Location.of("LOC-P", "Pickup");
    return PlannerFixtures.shell()
        .addCab(PlannerFixtures.cab("CAB-1", 12_800L))
        .addTrip(
            PlannerFixtures.trip(
                "TRIP-A",
                PlannerFixtures.START.plusSeconds(600),
                PlannerFixtures.depot(),
                drop,
                1_000))
        .addTrip(
            PlannerFixtures.trip(
                "TRIP-B",
                PlannerFixtures.START.plusSeconds(3_000),
                drop,
                PlannerFixtures.depot(),
                10_000))
        .build();
  }

  private static TripAssignment claimed(Trip trip, String cabId) {
    return new TripAssignment(
        trip, VehicleId.of(cabId), trip.startTs(), trip.plannedDropoffTs(), 0, 0, 0L, 1L, 1L);
  }
}
