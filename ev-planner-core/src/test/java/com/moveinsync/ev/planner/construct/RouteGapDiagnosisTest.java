package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.distance.MatrixDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class RouteGapDiagnosisTest {

  @Test
  void aCabWithNoPathToThePickupIsARouteMiss() {
    Location depot = PlannerFixtures.depot();
    Location pickup = Location.of("LOC-P", "Pickup");
    Location dropoff = Location.of("LOC-D", "Drop");
    PlannerInput fleet = fleet(depot, pickup, dropoff);
    SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 7L);
    FleetPlan plan =
        new FleetPlan(
            "plan-1",
            fleet.tenantId(),
            fleet.horizonStart(),
            fleet.horizonEnd(),
            List.of(
                new VehicleSchedule(
                    fleet.cabs().get(0).vehicle(),
                    depot,
                    50_000L,
                    PlannerFixtures.START,
                    List.of())),
            List.of(UnassignedTrip.of(fleet.trips().get(0), UnassignedReasonCode.OTHER, "parked")),
            1L,
            fleet.inputHash());

    TripDiagnosis diagnosis = AssignmentDiagnosis.explain(fleet, config, plan).get(0);

    assertThat(diagnosis.reasonCode()).isEqualTo(UnassignedReasonCode.OTHER);
    assertThat(diagnosis.explanation()).isEqualTo("no route from a cab to the pickup");
    assertThat(diagnosis.findings().get(0).constraint()).isEqualTo(BindingConstraint.ROUTE);
    assertThat(diagnosis.findings().get(0).arrivalSocWh()).isEmpty();
  }

  @Test
  void evidenceRejectsAHalfWindowAndANegativeArrival() {
    assertThatThrownBy(
            () ->
                new TripDiagnosis(
                    "TRIP-1",
                    UnassignedReasonCode.OTHER,
                    "parked",
                    Optional.empty(),
                    Optional.of(Instant.EPOCH),
                    Optional.empty(),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new CabFinding("CAB-1", BindingConstraint.ENERGY, OptionalLong.of(-1L), 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TimelineStep(
                    TimelineStep.Kind.TRIP,
                    "TRIP-1",
                    Instant.EPOCH.plusSeconds(1),
                    Instant.EPOCH,
                    1L,
                    1L,
                    0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static PlannerInput fleet(Location depot, Location pickup, Location dropoff) {
    return PlannerInput.builder()
        .tenantId(PlannerFixtures.TENANT)
        .horizon(PlannerFixtures.START, PlannerFixtures.END)
        .addHub(Hub.of(PlannerFixtures.TENANT.value(), "HUB-1", "Hub", depot, ZoneId.of("UTC"), 1))
        .addCharger(Charger.of(PlannerFixtures.TENANT.value(), "CHG-1", "HUB-1", 50))
        .addCab(PlannerFixtures.cab("CAB-1", 50_000L))
        .addTrip(
            PlannerFixtures.trip(
                "TRIP-R", PlannerFixtures.START.plusSeconds(3_600), pickup, dropoff, 10_000))
        .distanceProvider(
            new MatrixDistanceProvider(
                Map.of(
                    pair(pickup, dropoff),
                    DistanceDuration.of(10_000, 600),
                    pair(dropoff, depot),
                    DistanceDuration.of(5_000, 500))))
        .consumptionModel(new ConstantConsumptionModel())
        .build();
  }

  private static MatrixDistanceProvider.LocationPair pair(Location from, Location to) {
    return new MatrixDistanceProvider.LocationPair(from.id(), to.id());
  }
}
