package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.ChargerStatus;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.OutageWindow;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.SolveResult;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class ChargeInsertionTest {

  private final GreedyConstructionSolver solver = new GreedyConstructionSolver();
  private final SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 7L);
  private final Clock early = Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC);

  @Test
  void aLowBatteryCabChargesBeforeTheTrip() {
    PlannerInput fleet = chargeFleet();

    SolveResult result = solver.solve(fleet, config, early);
    FleetPlan plan = result.plan();
    ChargingSession session = onlySession(plan);
    TripAssignment trip = PlanViews.byTrip(plan).get("TRIP-C");
    long delivered =
        fleet
            .chargingCurve()
            .calculateTargetSocWh(session.startSocWh(), session.durationSeconds(), 60_000L, 50);
    long required =
        fleet
            .chargingCurve()
            .calculateDurationSeconds(session.startSocWh(), session.targetSocWh(), 60_000L, 50);

    assertThat(plan.totalAssignedTrips()).isEqualTo(1);
    assertThat(trip.vehicleId().value()).isEqualTo("CAB-1");
    assertThat(trip.deadheadDurationSeconds()).isZero();
    assertThat(session.startSocWh()).isEqualTo(12_000L);
    assertThat(session.targetSocWh()).isEqualTo(delivered);
    assertThat(session.durationSeconds()).isGreaterThanOrEqualTo(required);
    assertThat(session.targetSocWh()).isGreaterThan(session.startSocWh());
    assertThat(session.costMicros()).isZero();
    assertThat(trip.arrivalAtPickupSocWh())
        .isEqualTo(session.targetSocWh() - trip.deadheadEnergyWh());
  }

  @Test
  void anOutageDelaysTheSessionUntilThePlugIsBack() {
    PlannerInput fleet = outageFleet();

    ChargingSession session = onlySession(solver.solve(fleet, config, early).plan());

    assertThat(session.startTime()).isEqualTo(PlannerFixtures.START.plusSeconds(30));
  }

  @Test
  void onePlugLeavesTheSecondCabUnassignedForCongestion() {
    PlannerInput fleet = crowdedFleet();

    FleetPlan plan = solver.solve(fleet, config, early).plan();

    assertThat(plan.totalAssignedTrips()).isEqualTo(1);
    assertThat(plan.totalChargingSessions()).isEqualTo(1);
    assertThat(plan.unassignedTrips()).hasSize(1);
    assertThat(plan.unassignedTrips().get(0).reasonCode())
        .isEqualTo(UnassignedReasonCode.CHARGER_CONGESTION);
    assertThat(plan.unassignedTrips().get(0).explanation()).doesNotContain("@");
  }

  @Test
  void removalAndRepairKeepTheChargedTrip() {
    PlannerInput fleet = chargeFleet();
    FleetPlan built = solver.solve(fleet, config, early).plan();
    Clock open = Clock.fixed(PlannerFixtures.START.minusSeconds(10_000), ZoneOffset.UTC);

    var destroyed =
        new RandomTripRemoval().destroy(fleet, built, config, new SplittableRandom(3L), open);
    FleetPlan repaired =
        new RegretRepairOperator(2)
            .repair(fleet, destroyed, config, new SplittableRandom(3L), open);

    assertThat(destroyed.removedTripIds()).isNotEmpty();
    assertThat(repaired.totalAssignedTrips()).isEqualTo(1);
    assertThat(repaired.totalChargingSessions()).isEqualTo(1);
    assertThat(onlySession(repaired).startSocWh()).isEqualTo(12_000L);
  }

  private static PlannerInput chargeFleet() {
    return PlannerFixtures.shell()
        .addCab(PlannerFixtures.cab("CAB-1", 12_000L))
        .addTrip(
            PlannerFixtures.trip(
                "TRIP-C",
                PlannerFixtures.START.plusSeconds(3_600),
                Location.of("LOC-P", "Pickup"),
                Location.of("LOC-D", "Drop"),
                10_000))
        .build();
  }

  private static PlannerInput outageFleet() {
    Hub hub =
        Hub.of(
            PlannerFixtures.TENANT.value(),
            "HUB-1",
            "Hub",
            PlannerFixtures.depot(),
            ZoneId.of("UTC"),
            1);
    Charger charger =
        new Charger(
            PlannerFixtures.TENANT,
            ChargerId.of("CHG-1"),
            hub.id(),
            50,
            ChargerStatus.OPERATIONAL,
            List.of(
                new OutageWindow(
                    PlannerFixtures.START, PlannerFixtures.START.plusSeconds(30), "maintenance")));
    return base(hub, charger)
        .addCab(PlannerFixtures.cab("CAB-1", 12_000L))
        .addTrip(
            PlannerFixtures.trip(
                "TRIP-C",
                PlannerFixtures.START.plusSeconds(3_600),
                Location.of("LOC-P", "Pickup"),
                Location.of("LOC-D", "Drop"),
                10_000))
        .build();
  }

  private static PlannerInput crowdedFleet() {
    Hub hub =
        Hub.of(
            PlannerFixtures.TENANT.value(),
            "HUB-1",
            "Hub",
            PlannerFixtures.depot(),
            ZoneId.of("UTC"),
            1);
    Charger charger = Charger.of(PlannerFixtures.TENANT.value(), "CHG-1", "HUB-1", 50);
    return base(hub, charger)
        .addCab(PlannerFixtures.cab("CAB-1", 12_000L))
        .addCab(PlannerFixtures.cab("CAB-2", 12_000L))
        .addTrip(
            PlannerFixtures.trip(
                "TRIP-A",
                PlannerFixtures.START.plusSeconds(700),
                Location.of("LOC-P", "Pickup"),
                Location.of("LOC-D", "Drop"),
                10_000))
        .addTrip(
            PlannerFixtures.trip(
                "TRIP-B",
                PlannerFixtures.START.plusSeconds(700),
                Location.of("LOC-P", "Pickup"),
                Location.of("LOC-D", "Drop"),
                10_000))
        .build();
  }

  private static PlannerInput.Builder base(Hub hub, Charger charger) {
    return PlannerInput.builder()
        .tenantId(PlannerFixtures.TENANT)
        .horizon(PlannerFixtures.START, PlannerFixtures.END)
        .addHub(hub)
        .addCharger(charger)
        .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
        .consumptionModel(new ConstantConsumptionModel());
  }

  private static ChargingSession onlySession(FleetPlan plan) {
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof ChargingSession session) {
          return session;
        }
      }
    }
    throw new AssertionError("missing charging session");
  }
}
