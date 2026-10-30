package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class PlanValidatorTest {

  private Location hubLoc;
  private Location pickupLoc;
  private Location dropoffLoc;
  private Hub hub;
  private Charger charger;
  private Vehicle vehicle;
  private ValidationContext context;
  private PlanValidator validator;

  @BeforeEach
  void setUp() {
    hubLoc = Location.of("LOC-HUB-1", "Central Hub");
    pickupLoc = Location.of("LOC-PICKUP-1", "Office Pickup");
    dropoffLoc = Location.of("LOC-DROPOFF-1", "Tech Park Dropoff");

    hub = Hub.of("HUB-1", "Central Hub", hubLoc, ZoneId.of("Asia/Kolkata"), 1);
    charger = Charger.of("CHG-1", "HUB-1", 50);

    // 60 kWh battery, 150 Wh/km nominal consumption, 50 kW max charging power
    // 15% floor = 9,000 Wh
    vehicle = new Vehicle(VehicleId.of("CAB-01"), 60_000L, 150L, 50);

    // Distance matrix:
    // Hub -> Pickup: 5,000 m (5 km), 600s (10 min) -> 750 Wh
    // Pickup -> Dropoff: 10,000 m (10 km), 1200s (20 min) -> 1,500 Wh
    // Dropoff -> Hub: 5,000 m (5 km), 600s (10 min) -> 750 Wh
    Map<MatrixDistanceProvider.LocationPair, DistanceDuration> matrix =
        Map.of(
            new MatrixDistanceProvider.LocationPair(hubLoc.id(), pickupLoc.id()),
            DistanceDuration.of(5_000, 600),
            new MatrixDistanceProvider.LocationPair(pickupLoc.id(), dropoffLoc.id()),
            DistanceDuration.of(10_000, 1200),
            new MatrixDistanceProvider.LocationPair(dropoffLoc.id(), hubLoc.id()),
            DistanceDuration.of(5_000, 600));

    DistanceProvider distanceProvider =
        new MatrixDistanceProvider(matrix, new ConstantSpeedDistanceProvider(5_000, 10));

    context =
        ValidationContext.builder()
            .addHub(hub)
            .addCharger(charger)
            .consumptionModel(new ConstantConsumptionModel()) // 1.0x traffic, AC off
            .distanceProvider(distanceProvider)
            .chargingCurve(ChargingCurve.defaultCurve())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(true)
            .build();

    validator = new PlanValidator(context);
  }

  @Test
  @DisplayName("Valid plan satisfies all invariants H1-H6 and produces 0 violations")
  void validPlanValidation() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600); // 08:10:00Z
    Instant tDropoff = tPickup.plusSeconds(1200); // 08:30:00Z
    Instant tLatestFinish = tPickup.plusSeconds(1800); // 08:40:00Z

    Trip trip =
        new Trip(
            TripId.of("TRIP-1"),
            tPickup,
            pickupLoc,
            dropoffLoc,
            10_000,
            1200,
            tLatestFinish,
            "EMP-100");

    // Cab starts at Hub with 50,000 Wh (83.3% SoC)
    // Deadhead 5 km -> 750 Wh -> arrival at pickup = 49,250 Wh
    // Trip 10 km -> 1,500 Wh -> dropoff = 47,750 Wh
    TripAssignment assign =
        new TripAssignment(
            trip, vehicle.id(), tPickup, tDropoff, 5_000, 600, 750L, 49_250L, 47_750L);

    // After dropoff, return to hub (5 km, 600s, 750 Wh) -> arrive at hub with 47,000 Wh at
    // 08:40:00Z
    Instant tChargeStart = tDropoff.plusSeconds(600); // 08:40:00Z
    // Charge 47,000 Wh (78.3%) to 55,000 Wh (91.7%) at 50 kW
    // 47k to 48k (80% threshold): 1,000 Wh @ 50 kW = 72s
    // 48k to 55k: 7,000 Wh @ 25 kW = 1008s
    // Total duration = 1080s (18 min)
    Instant tChargeEnd = tChargeStart.plusSeconds(1080); // 08:58:00Z

    ChargingSession session =
        new ChargingSession(
            "SESS-1",
            vehicle.id(),
            hub.id(),
            charger.id(),
            tChargeStart,
            tChargeEnd,
            47_000L,
            55_000L,
            800_000L);

    VehicleSchedule schedule =
        new VehicleSchedule(vehicle, hubLoc, 50_000L, t0, List.of(assign, session));

    FleetPlan plan =
        new FleetPlan(
            "PLAN-01",
            "tenant-1",
            t0,
            tChargeEnd.plusSeconds(3600),
            List.of(schedule),
            List.of(),
            12345L,
            "hash-test");

    ValidationReport report = validator.validate(plan);

    assertThat(report.isValid()).isTrue();
    assertThat(report.violationCount()).isEqualTo(0);
    assertThat(report.totalTripsChecked()).isEqualTo(1);
    assertThat(report.totalSessionsChecked()).isEqualTo(1);
    assertThat(report.totalVehiclesChecked()).isEqualTo(1);
    report.assertValid();
  }

  @Test
  @DisplayName("H1: Violation caught when vehicle SoC drops below 15% floor during trip")
  void batteryFloorViolationOnTrip() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);
    Instant tDropoff = tPickup.plusSeconds(1200);

    Trip trip =
        new Trip(
            TripId.of("TRIP-LOW-BATTERY"),
            tPickup,
            pickupLoc,
            dropoffLoc,
            10_000,
            1200,
            tPickup.plusSeconds(1800),
            "EMP-101");

    // Floor is 9,000 Wh. Start with 10,000 Wh.
    // Deadhead 750 Wh -> arrival at pickup = 9,250 Wh
    // Trip 1,500 Wh -> dropoff = 7,750 Wh (BELOW 9,000 Wh floor!)
    TripAssignment assign =
        new TripAssignment(trip, vehicle.id(), tPickup, tDropoff, 5_000, 600, 750L, 9_250L, 7_750L);

    VehicleSchedule schedule = new VehicleSchedule(vehicle, hubLoc, 10_000L, t0, List.of(assign));

    FleetPlan plan =
        new FleetPlan(
            "PLAN-02",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(schedule),
            List.of(),
            12345L,
            "hash-test");

    ValidationReport report = validator.validate(plan);

    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H1_BATTERY_FLOOR)).isNotEmpty();
    assertThatThrownBy(report::assertValid).isInstanceOf(ValidationException.class);
  }

  @Test
  @DisplayName("H2: Violation caught when concurrent sessions exceed charger capacity")
  void chargerCapacityViolation() {
    Instant tStart = Instant.parse("2026-10-30T08:00:00Z");
    Instant tEnd = tStart.plusSeconds(1800);

    // Hub has chargerCount = 1
    // Vehicle 1 charging from 08:00 to 08:30
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

    // Vehicle 2 charging from 08:15 to 08:45 (overlaps session 1 on 1-charger hub)
    Vehicle vehicle2 = new Vehicle(VehicleId.of("CAB-02"), 60_000L, 150L, 50);
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
            "tenant-1",
            tStart,
            tEnd.plusSeconds(3600),
            List.of(schedule1, schedule2),
            List.of(),
            12345L,
            "hash-test");

    ValidationReport report = validator.validate(plan);

    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H2_CHARGER_CAPACITY)).isNotEmpty();
  }

  @Test
  @DisplayName("H3: Violation caught when trip start time does not match required startTs")
  void tripTimingStartViolation() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant requiredPickup = t0.plusSeconds(600); // 08:10:00Z

    Trip trip =
        new Trip(
            TripId.of("TRIP-TIMING-1"),
            requiredPickup,
            pickupLoc,
            dropoffLoc,
            10_000,
            1200,
            requiredPickup.plusSeconds(1800),
            "EMP-102");

    // Scheduled pickup is shifted by 1 second (08:10:01Z)
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
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(schedule),
            List.of(),
            12345L,
            "hash-test");

    ValidationReport report = validator.validate(plan);

    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H3_TRIP_TIMING)).isNotEmpty();
  }

  @Test
  @DisplayName("H4: Violation caught when the same trip is assigned to multiple vehicles")
  void doubleTripAssignmentViolation() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);

    Trip trip =
        new Trip(
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

    Vehicle vehicle2 = new Vehicle(VehicleId.of("CAB-02"), 60_000L, 150L, 50);
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
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(schedule1, schedule2),
            List.of(),
            12345L,
            "hash-test");

    ValidationReport report = validator.validate(plan);

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
            TripId.of("TRIP-ISOLATED"),
            tPickup,
            pickupLoc,
            isolatedDropoff,
            10_000,
            1200,
            tPickup.plusSeconds(1800),
            "EMP-104");

    // Floor is 9,000 Wh. Dropoff SoC is 9,100 Wh (above floor at dropoff).
    // But distance from isolated dropoff to Central Hub is 5,000 m -> requires 750 Wh!
    // Remaining upon reaching hub: 9,100 - 750 = 8,350 Wh < 9,000 Wh floor!
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
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(schedule),
            List.of(),
            12345L,
            "hash-test");

    ValidationReport report = validator.validate(plan);

    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H5_RESERVE_TO_HUB)).isNotEmpty();
  }

  @Test
  @DisplayName("H6: Violation caught when session duration is less than required by curve")
  void chargingCurveDurationViolation() {
    Instant tStart = Instant.parse("2026-10-30T08:00:00Z");
    // Charging 20,000 Wh to 40,000 Wh (20,000 Wh fast segment at 50 kW)
    // Required duration = 20,000 * 3600 / 50,000 = 1,440 seconds
    // Shaved duration = 1,439 seconds (1s too short!)
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
            "tenant-1",
            tStart,
            tStart.plusSeconds(7200),
            List.of(schedule),
            List.of(),
            12345L,
            "hash-test");

    ValidationReport report = validator.validate(plan);

    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H6_CHARGING_CURVE)).isNotEmpty();
  }
}
