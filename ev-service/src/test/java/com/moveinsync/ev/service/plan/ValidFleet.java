package com.moveinsync.ev.service.plan;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.construct.GreedyConstructionSolver;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.validator.ValidationInput;
import com.moveinsync.ev.validator.ValidationInputBuilder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** One cab and one trip that the greedy solver can assign inside the validator's floor. */
final class ValidFleet {

  static final TenantId TENANT = TenantId.of("tenant-a");
  static final Instant START = Instant.parse("2026-10-30T08:00:00Z");
  static final Instant END = START.plusSeconds(28_800);
  private static final Location DEPOT = Location.of("LOC-HUB", "Hub");

  private ValidFleet() {
    throw new IllegalStateException("utility");
  }

  static PlannerInput input() {
    Hub hub = Hub.of(TENANT.value(), "HUB-1", "Hub", DEPOT, ZoneId.of("UTC"), 4);
    return PlannerInput.builder()
        .tenantId(TENANT)
        .horizon(START, END)
        .addHub(hub)
        .addCharger(Charger.of(TENANT.value(), "CHG-1", "HUB-1", 50))
        .addCab(cab())
        .addTrip(trip())
        .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
        .consumptionModel(new ConstantConsumptionModel())
        .build();
  }

  static FleetPlan plan(PlannerInput fleet) {
    SolverConfig config = SolverConfig.safeBaseline(END, 11L);
    Clock clock = Clock.fixed(START, ZoneOffset.UTC);
    return new GreedyConstructionSolver().solve(fleet, config, clock).plan();
  }

  static ValidationInput groundTruth(PlannerInput fleet) {
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

  private static CabShift cab() {
    return new CabShift(
        new Vehicle(TENANT, VehicleId.of("CAB-1"), 60_000L, 150L, 50), DEPOT, 50_000L, START);
  }

  private static Trip trip() {
    Instant pickup = START.plusSeconds(3_600);
    return new Trip(
        TENANT,
        TripId.of("TRIP-1"),
        pickup,
        Location.of("LOC-P", "Pickup"),
        Location.of("LOC-D", "Drop"),
        2_000,
        1_200,
        pickup.plusSeconds(3_600),
        "EMP-1");
  }
}
