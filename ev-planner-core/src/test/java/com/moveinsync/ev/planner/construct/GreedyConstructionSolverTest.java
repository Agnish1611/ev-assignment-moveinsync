package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.consumption.ConsumptionModel;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GreedyConstructionSolverTest {

  private final GreedyConstructionSolver solver = new GreedyConstructionSolver();
  private final PlannerInput input = PlannerFixtures.sample();
  private final SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 42L);
  private final Clock early = Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC);

  @Test
  void assignsBothSampleTripsToTheCheaperCab() {
    var result = solver.solve(input, config, early);
    Map<String, TripAssignment> assigned = PlanViews.byTrip(result.plan());
    Vehicle cab = input.cabs().get(0).vehicle();
    ConsumptionModel model = input.consumptionModel();
    TripAssignment first = assigned.get("TRIP-1");
    long deadhead = model.calculateEnergyWh(cab, 5_000, first.trip().startTs(), false);
    long loaded =
        model.calculateEnergyWh(cab, first.trip().distanceMeters(), first.trip().startTs(), false);

    assertThat(result.deadlineExhausted()).isFalse();
    assertThat(result.plan().planId()).startsWith("greedy-");
    assertThat(result.plan().seed()).isEqualTo(42L);
    assertThat(result.plan().inputHash()).isEqualTo(input.inputHash());
    assertThat(result.score().backupTrips()).isZero();
    assertThat(assigned).containsOnlyKeys("TRIP-1", "TRIP-2");
    assertThat(first.vehicleId().value()).isEqualTo("CAB-1");
    assertThat(assigned.get("TRIP-2").vehicleId().value()).isEqualTo("CAB-1");
    assertThat(first.deadheadDurationSeconds()).isZero();
    assertThat(first.deadheadDistanceMeters()).isEqualTo(5_000);
    assertThat(first.deadheadEnergyWh()).isEqualTo(deadhead);
    assertThat(first.arrivalAtPickupSocWh()).isEqualTo(50_000L - deadhead);
    assertThat(first.departureAfterDropoffSocWh()).isEqualTo(50_000L - deadhead - loaded);
    assertThat(first.pickupTime()).isEqualTo(first.trip().startTs());
    assertThat(first.dropoffTime()).isEqualTo(first.trip().plannedDropoffTs());
    long secondDeadhead =
        model.calculateEnergyWh(cab, 5_000, assigned.get("TRIP-2").trip().startTs(), false);
    assertThat(assigned.get("TRIP-2").arrivalAtPickupSocWh())
        .isEqualTo(first.departureAfterDropoffSocWh() - secondDeadhead);
  }

  @Test
  void aSecondSolveMatchesTheFirst() {
    var first = solver.solve(input, config, early).plan();
    var second = solver.solve(input, config, early).plan();

    assertThat(PlanViews.byTrip(second).get("TRIP-1").vehicleId())
        .isEqualTo(PlanViews.byTrip(first).get("TRIP-1").vehicleId());
    assertThat(PlanViews.byTrip(second).get("TRIP-2").departureAfterDropoffSocWh())
        .isEqualTo(PlanViews.byTrip(first).get("TRIP-2").departureAfterDropoffSocWh());
  }

  @Test
  void scarceTripKeepsTheOnlyCabThatCanServeIt() {
    Instant start = PlannerFixtures.START.plusSeconds(600);
    Location from = Location.of("LOC-P", "Pickup");
    Location to = Location.of("LOC-D", "Drop");
    PlannerInput fleet =
        PlannerFixtures.shell()
            .addCab(PlannerFixtures.cab("CAB-POOR", 16_000L, 150L, PlannerFixtures.depot()))
            .addCab(PlannerFixtures.cab("CAB-RICH", 50_000L, 100L, PlannerFixtures.depot()))
            .addTrip(PlannerFixtures.trip("TRIP-A", start, from, to, 10_000))
            .addTrip(PlannerFixtures.trip("TRIP-B", start, from, to, 40_000))
            .build();

    Map<String, TripAssignment> assigned =
        PlanViews.byTrip(solver.solve(fleet, config, early).plan());

    assertThat(assigned.get("TRIP-B").vehicleId().value()).isEqualTo("CAB-RICH");
    assertThat(assigned.get("TRIP-A").vehicleId().value()).isEqualTo("CAB-POOR");
  }

  @Test
  void expiredDeadlineReturnsTheBackupPlan() {
    Clock late = Clock.fixed(PlannerFixtures.END, ZoneOffset.UTC);

    var result = solver.solve(input, config, late);

    assertThat(result.deadlineExhausted()).isTrue();
    assertThat(result.plan().totalAssignedTrips()).isZero();
    assertThat(result.plan().planId()).startsWith("backup-");
  }

  @Test
  void deadlineDuringSearchLeavesTheRestOnBackup() {
    Clock clock =
        new SteppingClock(PlannerFixtures.START, PlannerFixtures.START, PlannerFixtures.END);

    var result = solver.solve(input, config, clock);

    assertThat(result.deadlineExhausted()).isTrue();
    assertThat(PlanViews.byTrip(result.plan())).containsOnlyKeys("TRIP-1");
    assertThat(result.plan().unassignedTrips()).hasSize(1);
    assertThat(result.plan().unassignedTrips().get(0).trip().id().value()).isEqualTo("TRIP-2");
    assertThat(result.plan().unassignedTrips().get(0).reasonCode().name())
        .isEqualTo("SAFE_PATCH_FALLBACK");
  }

  @Test
  void emptyTripListProducesEmptySchedules() {
    PlannerInput fleet =
        PlannerFixtures.shell().addCab(PlannerFixtures.cab("CAB-1", 50_000L)).build();

    var result = solver.solve(fleet, config, early);

    assertThat(result.deadlineExhausted()).isFalse();
    assertThat(result.plan().totalAssignedTrips()).isZero();
    assertThat(result.plan().totalUnassignedTrips()).isZero();
  }

  @Test
  void acOnIsCopiedIntoTheEnergyClaim() {
    PlannerInput fleet =
        PlannerFixtures.shell()
            .acOn(true)
            .addCab(PlannerFixtures.cab("CAB-1", 50_000L))
            .addTrip(PlannerFixtures.trip("TRIP-1", PlannerFixtures.START.plusSeconds(600)))
            .build();
    long expected =
        fleet
            .consumptionModel()
            .calculateEnergyWh(
                fleet.cabs().get(0).vehicle(), 5_000, fleet.trips().get(0).startTs(), true);

    TripAssignment assignment =
        PlanViews.byTrip(solver.solve(fleet, config, early).plan()).get("TRIP-1");

    assertThat(assignment.deadheadEnergyWh()).isEqualTo(expected);
  }

  @Test
  void nullArgumentsAreRejected() {
    assertThatThrownBy(() -> solver.solve(null, config, early))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> solver.solve(input, null, early))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> solver.solve(input, config, null))
        .isInstanceOf(NullPointerException.class);
  }

  private static final class SteppingClock extends Clock {
    private final Instant[] ticks;
    private int index;

    private SteppingClock(Instant... ticks) {
      this.ticks = ticks;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      int cursor = Math.min(index, ticks.length - 1);
      if (index < ticks.length) {
        index++;
      }
      return ticks[cursor];
    }
  }
}
