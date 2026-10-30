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
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlanValidatorInvariantsTest {

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
        .consumptionModel(new ConstantConsumptionModel())
        .distanceProvider(distanceProvider)
        .chargingCurve(ChargingCurve.defaultCurve())
        .batteryFloorPercentage(15)
        .enforceReserveToHub(true)
        .addHub(hub)
        .addCharger(charger);
  }

  @Test
  @DisplayName("H4: Violation caught when the same trip is assigned to multiple vehicles")
  void doubleTripAssignmentViolation() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);

    Trip trip =
        new Trip(
            tenant,
            TripId.of("TRIP-SHARED"),
            tPickup,
            pickupLoc,
            dropoffLoc,
            10_000,
            1200,
            tPickup.plusSeconds(1800),
            "EMP-103");

    TripAssignment assign1 =
        new TripAssignment(
            trip,
            vehicle.id(),
            tPickup,
            tPickup.plusSeconds(1200),
            5_000,
            600,
            750L,
            49_250L,
            47_750L);
    VehicleSchedule schedule1 = new VehicleSchedule(vehicle, hubLoc, 50_000L, t0, List.of(assign1));

    Vehicle vehicle2 = new Vehicle(tenant, VehicleId.of("CAB-02"), 60_000L, 150L, 50);
    TripAssignment assign2 =
        new TripAssignment(
            trip,
            vehicle2.id(),
            tPickup,
            tPickup.plusSeconds(1200),
            5_000,
            600,
            750L,
            49_250L,
            47_750L);
    VehicleSchedule schedule2 =
        new VehicleSchedule(vehicle2, hubLoc, 50_000L, t0, List.of(assign2));

    FleetPlan plan =
        new FleetPlan(
            "PLAN-DOUBLE-ASSIGN",
            tenant.value(),
            t0,
            t0.plusSeconds(7200),
            List.of(schedule1, schedule2),
            List.of(),
            12345L,
            "hash-test");

    ValidationInput input =
        baseInputBuilder(plan)
            .addVehicle(vehicle, hubLoc, 50_000L)
            .addVehicle(vehicle2, hubLoc, 50_000L)
            .addTrip(trip)
            .build();

    ValidationReport report = validator.validate(input, plan);

    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H4_DOUBLE_ASSIGNMENT)).isNotEmpty();
  }

  @Test
  @DisplayName("H5: Violation caught when cab after dropoff cannot reach any hub above floor")
  void reserveToHubViolation() {
    Location isolatedDropoff = Location.of("LOC-ISOLATED", "Remote Location");
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);

    Trip trip =
        new Trip(
            tenant,
            TripId.of("TRIP-ISOLATED"),
            tPickup,
            pickupLoc,
            isolatedDropoff,
            10_000,
            1200,
            tPickup.plusSeconds(1800),
            "EMP-104");

    TripAssignment assign =
        new TripAssignment(
            trip,
            vehicle.id(),
            tPickup,
            tPickup.plusSeconds(1200),
            5_000,
            600,
            750L,
            10_600L,
            9_100L);

    VehicleSchedule schedule = new VehicleSchedule(vehicle, hubLoc, 11_350L, t0, List.of(assign));

    FleetPlan plan =
        new FleetPlan(
            "PLAN-RESERVE-FAIL",
            tenant.value(),
            t0,
            t0.plusSeconds(7200),
            List.of(schedule),
            List.of(),
            12345L,
            "hash-test");

    ValidationInput input =
        baseInputBuilder(plan).addVehicle(vehicle, hubLoc, 11_350L).addTrip(trip).build();

    ValidationReport report = validator.validate(input, plan);

    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H5_RESERVE_TO_HUB)).isNotEmpty();
  }

  @Test
  @DisplayName("H6: Violation caught when session duration is less than required by curve")
  void chargingCurveDurationViolation() {
    Instant tStart = Instant.parse("2026-10-30T08:00:00Z");
    Instant tEndShaved = tStart.plusSeconds(1439);

    ChargingSession session =
        new ChargingSession(
            "SESS-SHORT",
            vehicle.id(),
            hub.id(),
            charger.id(),
            tStart,
            tEndShaved,
            20_000L,
            40_000L,
            500_000L);

    VehicleSchedule schedule =
        new VehicleSchedule(vehicle, hubLoc, 20_000L, tStart, List.of(session));

    FleetPlan plan =
        new FleetPlan(
            "PLAN-CURVE-VIOLATION",
            tenant.value(),
            tStart,
            tStart.plusSeconds(7200),
            List.of(schedule),
            List.of(),
            12345L,
            "hash-test");

    ValidationInput input = baseInputBuilder(plan).addVehicle(vehicle, hubLoc, 20_000L).build();

    ValidationReport report = validator.validate(input, plan);

    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H6_CHARGING_CURVE)).isNotEmpty();
  }
}
