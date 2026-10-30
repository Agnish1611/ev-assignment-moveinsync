package com.moveinsync.ev.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.tariff.TariffSchedule;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.construct.AlnsFleetSolver;
import com.moveinsync.ev.planner.construct.GreedyConstructionSolver;
import com.moveinsync.ev.planner.construct.RandomTripRemoval;
import com.moveinsync.ev.planner.construct.RegretRepairOperator;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.SolveResult;
import com.moveinsync.ev.validator.PlanValidator;
import com.moveinsync.ev.validator.ValidationInput;
import com.moveinsync.ev.validator.ValidationInputBuilder;
import com.moveinsync.ev.validator.ValidationReport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The planner cannot depend on the validator. This module runs the constructed plan through {@link
 * PlanValidator} at the validator's 15 percent floor.
 */
class GreedyBaselineValidatorTest {

  private static final TenantId TENANT = TenantId.of("tenant-a");
  private static final Instant START = Instant.parse("2026-10-30T08:00:00Z");
  private static final Instant END = START.plusSeconds(28_800);
  private static final Location DEPOT = Location.of("LOC-HUB", "Hub");
  private static final Clock EARLY = Clock.fixed(START, ZoneOffset.UTC);
  private static final SolverConfig CONFIG = SolverConfig.safeBaseline(END, 7L);

  private final GreedyConstructionSolver solver = new GreedyConstructionSolver();

  @Test
  void chainedTripsPassTheValidator() {
    PlannerInput fleet =
        shell(1)
            .addCab(cab("CAB-1", 50_000L, DEPOT))
            .addTrip(
                trip(
                    "TRIP-1",
                    START.plusSeconds(600),
                    Location.of("LOC-P", "Pickup"),
                    Location.of("LOC-D", "Drop")))
            .addTrip(
                trip(
                    "TRIP-2",
                    START.plusSeconds(3_600),
                    Location.of("LOC-P", "Pickup"),
                    Location.of("LOC-D", "Drop")))
            .build();

    SolveResult result = solver.solve(fleet, CONFIG, EARLY);

    assertThat(result.plan().totalAssignedTrips()).isEqualTo(2);
    assertValid(fleet, result.plan());
  }

  @Test
  void twentyCabBaselinePassesTheValidator() {
    PlannerInput fleet = twentyCabs();

    SolveResult result = solver.solve(fleet, CONFIG, EARLY);

    assertThat(result.plan().totalAssignedTrips()).isEqualTo(20);
    assertThat(result.plan().totalUnassignedTrips()).isZero();
    assertValid(fleet, result.plan());
  }

  @Test
  void planningFloorLeavesATightTripUnassignedAndThePlanStillValidates() {
    PlannerInput fleet =
        shell(1)
            .addCab(cab("CAB-1", 11_000L, DEPOT))
            .addTrip(trip("TRIP-T", START.plusSeconds(30), DEPOT, DEPOT))
            .build();

    SolveResult result = solver.solve(fleet, CONFIG, EARLY);

    assertThat(result.plan().totalAssignedTrips()).isZero();
    assertValid(fleet, result.plan());
  }

  @Test
  void removalAndRepairStillPassTheValidator() {
    PlannerInput fleet = twentyCabs();
    FleetPlan built = solver.solve(fleet, CONFIG, EARLY).plan();
    Clock open = Clock.fixed(START.minusSeconds(10_000), ZoneOffset.UTC);
    var destroyed =
        new RandomTripRemoval().destroy(fleet, built, CONFIG, new SplittableRandom(11L), open);
    FleetPlan repaired =
        new RegretRepairOperator(2)
            .repair(fleet, destroyed, CONFIG, new SplittableRandom(11L), open);

    assertThat(destroyed.removedTripIds()).isNotEmpty();
    assertThat(repaired.totalAssignedTrips()).isEqualTo(20);
    assertValid(fleet, repaired);
  }

  @Test
  void aChargeStopPassesTheValidator() {
    PlannerInput fleet =
        shell(1)
            .addCab(cab("CAB-1", 12_000L, DEPOT))
            .addTrip(
                trip(
                    "TRIP-C",
                    START.plusSeconds(3_600),
                    Location.of("LOC-P", "Pickup"),
                    Location.of("LOC-D", "Drop")))
            .addTariff(
                new TariffSchedule(
                    HubId.of("HUB-1"),
                    List.of(new UtcTariffWindow(HubId.of("HUB-1"), START, END, 100_000L))))
            .build();

    SolveResult result = solver.solve(fleet, CONFIG, EARLY);

    assertThat(result.plan().totalAssignedTrips()).isEqualTo(1);
    assertThat(result.plan().totalChargingSessions()).isEqualTo(1);
    assertThat(result.plan().totalChargingCostMicros()).isPositive();
    assertValid(fleet, result.plan());
  }

  @Test
  void searchOnTheBaselineStillPassesTheValidator() {
    PlannerInput fleet = twentyCabs();
    SolverConfig search = CONFIG.withIterations(4);

    SolveResult result = new AlnsFleetSolver().solve(fleet, search, EARLY);

    assertThat(result.plan().totalAssignedTrips()).isEqualTo(20);
    assertThat(result.deadlineExhausted()).isFalse();
    assertValid(fleet, result.plan());
  }

  private static void assertValid(PlannerInput fleet, FleetPlan plan) {
    ValidationReport report = new PlanValidator().validate(groundTruth(fleet), plan);
    assertThat(report.isValid()).as(report.toDetailedSummary()).isTrue();
  }

  private static PlannerInput twentyCabs() {
    PlannerInput.Builder builder = shell(20);
    for (int i = 0; i < 20; i++) {
      String suffix = i < 10 ? "0" + i : Integer.toString(i);
      builder.addCab(cab("CAB-" + suffix, 50_000L, DEPOT));
      builder.addTrip(
          trip(
              "TRIP-" + suffix,
              START.plusSeconds(600),
              DEPOT,
              Location.of("LOC-" + suffix, "Stop")));
    }
    return builder.build();
  }

  private static PlannerInput.Builder shell(int chargerCount) {
    Hub hub = Hub.of(TENANT.value(), "HUB-1", "Hub", DEPOT, ZoneId.of("UTC"), chargerCount);
    Charger charger = Charger.of(TENANT.value(), "CHG-1", "HUB-1", 50);
    return PlannerInput.builder()
        .tenantId(TENANT)
        .horizon(START, END)
        .addHub(hub)
        .addCharger(charger)
        .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
        .consumptionModel(new ConstantConsumptionModel());
  }

  private static CabShift cab(String id, long socWh, Location location) {
    return new CabShift(
        new Vehicle(TENANT, VehicleId.of(id), 60_000L, 150L, 50), location, socWh, START);
  }

  private static Trip trip(String id, Instant pickup, Location from, Location to) {
    return new Trip(
        TENANT, TripId.of(id), pickup, from, to, 8_000, 900, pickup.plusSeconds(3_600), "EMP-1");
  }

  private static ValidationInput groundTruth(PlannerInput fleet) {
    ValidationInputBuilder builder =
        new ValidationInputBuilder()
            .tenantId(fleet.tenantId())
            .horizon(fleet.horizonStart(), fleet.horizonEnd())
            .distanceProvider(fleet.distanceProvider())
            .consumptionModel(fleet.consumptionModel())
            .chargingCurve(fleet.chargingCurve())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(true)
            .idlePowerW(0);
    fleet
        .cabs()
        .forEach(cab -> builder.addVehicle(cab.vehicle(), cab.startLocation(), cab.initialSocWh()));
    fleet.trips().forEach(builder::addTrip);
    fleet.hubs().forEach(builder::addHub);
    fleet.chargers().forEach(builder::addCharger);
    fleet.tariffs().forEach(schedule -> schedule.windows().forEach(builder::addUtcTariff));
    return builder.build();
  }
}
