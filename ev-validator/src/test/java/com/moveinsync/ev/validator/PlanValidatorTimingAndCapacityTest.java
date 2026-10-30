package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.charging.ChargingCurve;
import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.distance.MatrixDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tariff.TariffWindow;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Restored and strengthened tests for H2 (charger capacity), H3 (trip timing), and session cost
 * boundaries (+1/-1 micro-unit).
 */
class PlanValidatorTimingAndCapacityTest {

  private TenantId tenant;
  private Location hubLoc;
  private Location pickupLoc;
  private Location dropoffLoc;
  private Hub hub;
  private Charger charger;
  private Vehicle vehicle;
  private DistanceProvider distanceProvider;
  private PlanValidator validator;

  @BeforeEach
  void setUp() {
    tenant = TenantId.of("tenant-1");
    hubLoc = Location.of("LOC-HUB-1", "Central Hub");
    pickupLoc = Location.of("LOC-PICKUP-1", "Office Pickup");
    dropoffLoc = Location.of("LOC-DROPOFF-1", "Tech Park Dropoff");

    hub = Hub.of("tenant-1", "HUB-1", "Central Hub", hubLoc, ZoneId.of("Asia/Kolkata"), 1);
    charger = Charger.of("tenant-1", "CHG-1", "HUB-1", 50);

    vehicle = new Vehicle(tenant, VehicleId.of("CAB-01"), 60_000L, 150L, 50);

    Map<MatrixDistanceProvider.LocationPair, DistanceDuration> matrix =
        Map.of(
            new MatrixDistanceProvider.LocationPair(hubLoc.id(), pickupLoc.id()),
            DistanceDuration.of(5_000, 600),
            new MatrixDistanceProvider.LocationPair(pickupLoc.id(), dropoffLoc.id()),
            DistanceDuration.of(10_000, 1200),
            new MatrixDistanceProvider.LocationPair(dropoffLoc.id(), hubLoc.id()),
            DistanceDuration.of(5_000, 600));

    distanceProvider =
        new MatrixDistanceProvider(matrix, new ConstantSpeedDistanceProvider(5_000, 10));

    validator = new PlanValidator();
  }

  private ValidationInputBuilder baseInputBuilder(FleetPlan plan) {
    return ValidationInput.builder()
        .tenantId(tenant)
        .horizon(plan.horizonStart(), plan.horizonEnd())
        .addHub(hub)
        .addCharger(charger)
        .distanceProvider(distanceProvider)
        .consumptionModel(new ConstantConsumptionModel())
        .chargingCurve(ChargingCurve.defaultCurve())
        .batteryFloorPercentage(15)
        .enforceReserveToHub(true);
  }

  @Test
  @DisplayName("H2: Violation caught when concurrent sessions exceed charger capacity")
  void chargerCapacityViolation() {
    Instant tStart = Instant.parse("2026-10-30T08:00:00Z");
    Instant tEnd = tStart.plusSeconds(1800);

    ChargingSession session1 =
        new ChargingSession(
            "SESS-1",
            vehicle.id(),
            hub.id(),
            charger.id(),
            tStart,
            tEnd,
            20_000L,
            40_000L,
            500_000L);
    VehicleSchedule schedule1 =
        new VehicleSchedule(vehicle, hubLoc, 20_000L, tStart, List.of(session1));

    Vehicle vehicle2 = new Vehicle(tenant, VehicleId.of("CAB-02"), 60_000L, 150L, 50);
    ChargingSession session2 =
        new ChargingSession(
            "SESS-2",
            vehicle2.id(),
            hub.id(),
            charger.id(),
            tStart.plusSeconds(900),
            tEnd.plusSeconds(900),
            20_000L,
            40_000L,
            500_000L);
    VehicleSchedule schedule2 =
        new VehicleSchedule(vehicle2, hubLoc, 20_000L, tStart, List.of(session2));

    FleetPlan plan =
        new FleetPlan(
            "PLAN-OVERCAPACITY",
            tenant.value(),
            tStart,
            tEnd.plusSeconds(3600),
            List.of(schedule1, schedule2),
            List.of(),
            12345L,
            "hash-test");

    ValidationInput input =
        baseInputBuilder(plan)
            .addVehicle(vehicle, hubLoc, 20_000L)
            .addVehicle(vehicle2, hubLoc, 20_000L)
            .build();

    ValidationReport report = validator.validate(input, plan);

    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H2_CHARGER_CAPACITY)).isNotEmpty();
  }

  @Test
  @DisplayName("H3: Violation caught when trip start time does not match required startTs")
  void tripTimingStartViolation() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant requiredPickup = t0.plusSeconds(600);

    Trip trip =
        new Trip(
            tenant,
            TripId.of("TRIP-TIMING-1"),
            requiredPickup,
            pickupLoc,
            dropoffLoc,
            10_000,
            1200,
            requiredPickup.plusSeconds(1800),
            "EMP-102");

    Instant actualPickup = requiredPickup.plusSeconds(1);
    TripAssignment assign =
        new TripAssignment(
            trip,
            vehicle.id(),
            actualPickup,
            actualPickup.plusSeconds(1200),
            5_000,
            600,
            750L,
            49_250L,
            47_750L);

    VehicleSchedule schedule = new VehicleSchedule(vehicle, hubLoc, 50_000L, t0, List.of(assign));

    FleetPlan plan =
        new FleetPlan(
            "PLAN-TIMING",
            tenant.value(),
            t0,
            t0.plusSeconds(7200),
            List.of(schedule),
            List.of(),
            12345L,
            "hash-test");

    ValidationInput input =
        baseInputBuilder(plan).addVehicle(vehicle, hubLoc, 50_000L).addTrip(trip).build();

    ValidationReport report = validator.validate(input, plan);

    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H3_TRIP_TIMING)).isNotEmpty();
  }

  @Test
  @DisplayName("Tariff cost: session cost +1 micro-unit is rejected with COST_MISMATCH")
  void sessionCostPlusOneMicroUnitRejected() {
    Instant t0 = Instant.parse("2026-10-30T00:00:00Z");
    Instant tEnd = t0.plusSeconds(1440); // 20,000 Wh @ 50 kW -> 1440s
    // 20 kWh @ 100,000 micros/kWh = 2,000,000 micros
    long trueCost = 2_000_000L;

    ChargingSession session =
        new ChargingSession(
            "S-COST-PLUS-1",
            vehicle.id(),
            hub.id(),
            charger.id(),
            t0,
            tEnd,
            20_000L,
            40_000L,
            trueCost + 1L);

    VehicleSchedule schedule = new VehicleSchedule(vehicle, hubLoc, 20_000L, t0, List.of(session));
    FleetPlan plan =
        new FleetPlan(
            "P-COST-PLUS-1",
            tenant.value(),
            t0,
            t0.plusSeconds(7200),
            List.of(schedule),
            List.of(),
            1L,
            "h");

    ValidationInput input =
        baseInputBuilder(plan)
            .addVehicle(vehicle, hubLoc, 20_000L)
            .addTariff(
                new TariffWindow(hub.id(), LocalTime.of(0, 0), LocalTime.of(23, 59), 100_000L))
            .build();

    ValidationReport report = validator.validate(input, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.COST_MISMATCH)).isNotEmpty();
  }

  @Test
  @DisplayName("Tariff cost: session cost -1 micro-unit is rejected with COST_MISMATCH")
  void sessionCostMinusOneMicroUnitRejected() {
    Instant t0 = Instant.parse("2026-10-30T00:00:00Z");
    Instant tEnd = t0.plusSeconds(1440);
    long trueCost = 2_000_000L;

    ChargingSession session =
        new ChargingSession(
            "S-COST-MINUS-1",
            vehicle.id(),
            hub.id(),
            charger.id(),
            t0,
            tEnd,
            20_000L,
            40_000L,
            trueCost - 1L);

    VehicleSchedule schedule = new VehicleSchedule(vehicle, hubLoc, 20_000L, t0, List.of(session));
    FleetPlan plan =
        new FleetPlan(
            "P-COST-MINUS-1",
            tenant.value(),
            t0,
            t0.plusSeconds(7200),
            List.of(schedule),
            List.of(),
            1L,
            "h");

    ValidationInput input =
        baseInputBuilder(plan)
            .addVehicle(vehicle, hubLoc, 20_000L)
            .addTariff(
                new TariffWindow(hub.id(), LocalTime.of(0, 0), LocalTime.of(23, 59), 100_000L))
            .build();

    ValidationReport report = validator.validate(input, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.COST_MISMATCH)).isNotEmpty();
  }
}
