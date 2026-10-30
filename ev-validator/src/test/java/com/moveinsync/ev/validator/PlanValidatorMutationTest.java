package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.charging.ChargingCurve;
import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.distance.MatrixDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerStatus;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.OutageWindow;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mutation tests for the independent plan validator.
 *
 * <p>Takes a known-valid baseline plan, injects each individual violation type:
 *
 * <ul>
 *   <li>Shave 1 Wh below the 15% floor
 *   <li>Overlap two charging sessions by 1 second
 *   <li>Shift trip pickup by 1 second
 *   <li>Shift trip dropoff past deadline by 1 second
 *   <li>Double-assign a trip to multiple cabs
 *   <li>Overlap activities on the same cab by 1 second
 *   <li>Shave reserve-to-hub charge below floor
 *   <li>Shave 1 second from charging curve duration
 *   <li>Inflate target SoC by 1 Wh without duration increase
 * </ul>
 *
 * And asserts that the validator catches every single mutated violation.
 */
class PlanValidatorMutationTest {

  private Location hub1Loc;
  private Location pickupLoc;
  private Location dropoffLoc;
  private Hub hub1;
  private Charger charger1;
  private Vehicle cab1;
  private Vehicle cab2;
  private ValidationContext context;
  private PlanValidator validator;

  private Trip baselineTrip;
  private FleetPlan baselineValidPlan;

  @BeforeEach
  void setUp() {
    hub1Loc = Location.of("LOC-HUB-1", "North Hub");
    pickupLoc = Location.of("LOC-PICKUP", "Pickup A");
    dropoffLoc = Location.of("LOC-DROPOFF", "Dropoff B");

    hub1 = Hub.of("HUB-1", "North Hub", hub1Loc, ZoneId.of("Asia/Kolkata"), 1); // 1 plug capacity
    charger1 = Charger.of("CHG-1", "HUB-1", 50);

    // 60,000 Wh capacity, 150 Wh/km nominal consumption -> 15% floor = 9,000 Wh
    cab1 = new Vehicle(VehicleId.of("CAB-01"), 60_000L, 150L, 50);
    cab2 = new Vehicle(VehicleId.of("CAB-02"), 60_000L, 150L, 50);

    Map<MatrixDistanceProvider.LocationPair, DistanceDuration> matrix =
        Map.of(
            new MatrixDistanceProvider.LocationPair(hub1Loc.id(), pickupLoc.id()),
            DistanceDuration.of(5_000, 600), // 5 km, 10 min -> 750 Wh
            new MatrixDistanceProvider.LocationPair(pickupLoc.id(), dropoffLoc.id()),
            DistanceDuration.of(10_000, 1200), // 10 km, 20 min -> 1,500 Wh
            new MatrixDistanceProvider.LocationPair(dropoffLoc.id(), hub1Loc.id()),
            DistanceDuration.of(5_000, 600)); // 5 km, 10 min -> 750 Wh

    DistanceProvider distanceProvider =
        new MatrixDistanceProvider(matrix, new ConstantSpeedDistanceProvider(5_000, 10));

    context =
        ValidationContext.builder()
            .addHub(hub1)
            .addCharger(charger1)
            .consumptionModel(new ConstantConsumptionModel())
            .distanceProvider(distanceProvider)
            .chargingCurve(ChargingCurve.defaultCurve())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(true)
            .build();

    validator = new PlanValidator(context);

    // Build standard valid baseline plan
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600); // 08:10:00Z
    Instant tDropoff = tPickup.plusSeconds(1200); // 08:30:00Z
    Instant tLatestFinish = tPickup.plusSeconds(1800); // 08:40:00Z

    baselineTrip =
        new Trip(
            TripId.of("TRIP-BASE"),
            tPickup,
            pickupLoc,
            dropoffLoc,
            10_000,
            1200,
            tLatestFinish,
            "EMP-MUT-01");

    TripAssignment assign =
        new TripAssignment(
            baselineTrip, cab1.id(), tPickup, tDropoff, 5_000, 600, 750L, 49_250L, 47_750L);

    // Charge at Hub 1 from 08:40:00 to 08:58:00 (1080 seconds = 18 minutes)
    // 47,000 Wh to 55,000 Wh at 50 kW
    Instant tChargeStart = tDropoff.plusSeconds(600);
    Instant tChargeEnd = tChargeStart.plusSeconds(1080);

    ChargingSession session =
        new ChargingSession(
            "SESS-BASE",
            cab1.id(),
            hub1.id(),
            charger1.id(),
            tChargeStart,
            tChargeEnd,
            47_000L,
            55_000L,
            750_000L);

    VehicleSchedule schedule1 =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(assign, session));

    baselineValidPlan =
        new FleetPlan(
            "PLAN-BASELINE",
            "tenant-1",
            t0,
            tChargeEnd.plusSeconds(3600),
            List.of(schedule1),
            List.of(),
            999L,
            "hash-base");

    // Verify that baseline is 100% valid before mutating
    ValidationReport baselineReport = validator.validate(baselineValidPlan);
    assertThat(baselineReport.isValid()).isTrue();
  }

  @Test
  @DisplayName("Mutation 1: Shaving 1 Wh below 15% floor at pickup is caught (H1)")
  void mutationShave1WhBelowFloorAtPickup() {
    // Floor is 9,000 Wh.
    // Set initial SoC to 9,749 Wh. Deadhead requires 750 Wh -> arrival SoC = 8,999 Wh (1 Wh below
    // floor!)
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);
    Instant tDropoff = tPickup.plusSeconds(1200);

    TripAssignment mutatedAssign =
        new TripAssignment(
            baselineTrip, cab1.id(), tPickup, tDropoff, 5_000, 600, 750L, 8_999L, 7_499L);

    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, hub1Loc, 9_749L, t0, List.of(mutatedAssign));

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-1",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-1");

    ValidationReport report = validator.validate(mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H1_BATTERY_FLOOR)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 2: Shaving 1 Wh below 15% floor at dropoff is caught (H1)")
  void mutationShave1WhBelowFloorAtDropoff() {
    // Floor is 9,000 Wh.
    // Initial SoC = 11,249 Wh. Deadhead 750 Wh -> pickup = 10,499 Wh. Trip 1,500 Wh -> dropoff =
    // 8,999 Wh (1 Wh below floor!)
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);
    Instant tDropoff = tPickup.plusSeconds(1200);

    TripAssignment mutatedAssign =
        new TripAssignment(
            baselineTrip, cab1.id(), tPickup, tDropoff, 5_000, 600, 750L, 10_499L, 8_999L);

    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, hub1Loc, 11_249L, t0, List.of(mutatedAssign));

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-2",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-2");

    ValidationReport report = validator.validate(mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H1_BATTERY_FLOOR)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 3: Overlapping two charging sessions by 1 second is caught (H2)")
  void mutationOverlapSessionsBy1Second() {
    // Hub 1 has 1 charger.
    // Baseline session is 08:40:00 to 08:58:00.
    // Add second session starting at 08:57:59 (1 second overlap with session 1).
    Instant tStart = Instant.parse("2026-10-30T08:57:59Z");
    Instant tEnd = tStart.plusSeconds(1440);

    ChargingSession overlappingSession =
        new ChargingSession(
            "SESS-OVERLAP-1S",
            cab2.id(),
            hub1.id(),
            charger1.id(),
            tStart,
            tEnd,
            20_000L,
            40_000L,
            500_000L);

    VehicleSchedule schedule2 =
        new VehicleSchedule(cab2, hub1Loc, 20_000L, tStart, List.of(overlappingSession));

    List<VehicleSchedule> schedules = new ArrayList<>(baselineValidPlan.vehicleSchedules());
    schedules.add(schedule2);

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-3",
            "tenant-1",
            baselineValidPlan.horizonStart(),
            tEnd.plusSeconds(3600),
            schedules,
            List.of(),
            999L,
            "hash-mut-3");

    ValidationReport report = validator.validate(mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H2_CHARGER_CAPACITY)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 4: Shifting trip pickup by +1 second is caught (H3)")
  void mutationShiftPickupBy1SecondLate() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant shiftedPickup = baselineTrip.startTs().plusSeconds(1); // 08:10:01Z instead of 08:10:00Z
    Instant dropoff = shiftedPickup.plusSeconds(1200);

    TripAssignment mutatedAssign =
        new TripAssignment(
            baselineTrip, cab1.id(), shiftedPickup, dropoff, 5_000, 600, 750L, 49_250L, 47_750L);

    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(mutatedAssign));

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-4",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-4");

    ValidationReport report = validator.validate(mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H3_TRIP_TIMING)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 5: Shifting trip pickup by -1 second early is caught (H3)")
  void mutationShiftPickupBy1SecondEarly() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant shiftedPickup =
        baselineTrip.startTs().minusSeconds(1); // 08:09:59Z instead of 08:10:00Z
    Instant dropoff = shiftedPickup.plusSeconds(1200);

    TripAssignment mutatedAssign =
        new TripAssignment(
            baselineTrip, cab1.id(), shiftedPickup, dropoff, 5_000, 600, 750L, 49_250L, 47_750L);

    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(mutatedAssign));

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-5",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-5");

    ValidationReport report = validator.validate(mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H3_TRIP_TIMING)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 6: Shifting dropoff by 1 second past deadline is caught (H3)")
  void mutationShiftDropoffPastDeadline() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant pickup = baselineTrip.startTs();
    // Latest finish is 08:40:00Z. Shift dropoff to 08:40:01Z
    Instant lateDropoff = baselineTrip.latestFinishTs().plusSeconds(1);

    TripAssignment mutatedAssign =
        new TripAssignment(
            baselineTrip, cab1.id(), pickup, lateDropoff, 5_000, 600, 750L, 49_250L, 47_750L);

    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(mutatedAssign));

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-6",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-6");

    ValidationReport report = validator.validate(mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H3_TRIP_TIMING)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 7: Double-assigning the same trip to two cabs is caught (H4)")
  void mutationDoubleAssignTrip() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant pickup = baselineTrip.startTs();
    Instant dropoff = pickup.plusSeconds(1200);

    TripAssignment assignCab1 =
        new TripAssignment(
            baselineTrip, cab1.id(), pickup, dropoff, 5_000, 600, 750L, 49_250L, 47_750L);
    VehicleSchedule schedule1 =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(assignCab1));

    // Cab 2 is also assigned baselineTrip
    TripAssignment assignCab2 =
        new TripAssignment(
            baselineTrip, cab2.id(), pickup, dropoff, 5_000, 600, 750L, 49_250L, 47_750L);
    VehicleSchedule schedule2 =
        new VehicleSchedule(cab2, hub1Loc, 50_000L, t0, List.of(assignCab2));

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-7",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(schedule1, schedule2),
            List.of(),
            999L,
            "hash-mut-7");

    ValidationReport report = validator.validate(mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H4_DOUBLE_ASSIGNMENT)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 8: Overlapping two activities on the same cab by 1 second is caught (H4)")
  void mutationOverlapActivitiesOnSameCab() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant pickup1 = t0.plusSeconds(600); // 08:10:00Z
    Instant dropoff1 = pickup1.plusSeconds(1200); // 08:30:00Z

    Trip trip1 =
        new Trip(
            TripId.of("T1"),
            pickup1,
            pickupLoc,
            dropoffLoc,
            10_000,
            1200,
            pickup1.plusSeconds(1800),
            "EMP-1");
    TripAssignment assign1 =
        new TripAssignment(trip1, cab1.id(), pickup1, dropoff1, 5_000, 600, 750L, 49_250L, 47_750L);

    // Trip 2 pickup starts at 08:29:59Z (1 second before dropoff1 at 08:30:00Z)
    Instant pickup2 = dropoff1.minusSeconds(1);
    Trip trip2 =
        new Trip(
            TripId.of("T2"),
            pickup2,
            dropoffLoc,
            pickupLoc,
            10_000,
            1200,
            pickup2.plusSeconds(1800),
            "EMP-2");
    TripAssignment assign2 =
        new TripAssignment(
            trip2, cab1.id(), pickup2, pickup2.plusSeconds(1200), 0, 0, 0L, 47_750L, 46_250L);

    // Overlapping charging session starting 1s before dropoff1
    Instant chargeStart = dropoff1.minusSeconds(1);
    ChargingSession overlappingSession =
        new ChargingSession(
            "SESS-OVERLAP-1",
            cab1.id(),
            hub1.id(),
            charger1.id(),
            chargeStart,
            chargeStart.plusSeconds(1000),
            40_000L,
            50_000L,
            500_000L);

    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(assign1, overlappingSession));

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-8",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-8");

    ValidationReport report = validator.validate(mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H4_ASSIGNMENT_OVERLAP)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 9: Shaving reserve-to-hub charge by 1 Wh below floor is caught (H5)")
  void mutationReserveToHubViolation() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);
    Instant tDropoff = tPickup.plusSeconds(1200);

    // Floor = 9,000 Wh. Distance from dropoff to nearest hub (Hub 1) is 5 km = 750 Wh.
    // Required dropoff SoC to reach hub above floor = 9,000 + 750 = 9,750 Wh.
    // If dropoff SoC is 9,749 Wh (1 Wh short!), arrival at hub will be 8,999 Wh (< 9,000 Wh floor).
    TripAssignment mutatedAssign =
        new TripAssignment(
            baselineTrip, cab1.id(), tPickup, tDropoff, 5_000, 600, 750L, 11_249L, 9_749L);

    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, hub1Loc, 11_999L, t0, List.of(mutatedAssign));

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-9",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-9");

    ValidationReport report = validator.validate(mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H5_RESERVE_TO_HUB)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 10: Shaving 1 second from charging curve duration is caught (H6)")
  void mutationShave1SecondFromChargingDuration() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tStart = t0.plusSeconds(2400);

    // 47,000 Wh to 55,000 Wh at 50 kW on 60 kWh pack:
    // Fast segment: 47k to 48k (80% threshold) = 1,000 Wh @ 50 kW = 72s
    // Slow segment: 48k to 55k = 7,000 Wh @ 25 kW = 1008s
    // Total required = 1080s
    // Mutate duration to 1079s (shaved by 1s!)
    Instant tEndShaved = tStart.plusSeconds(1079);

    ChargingSession shavedSession =
        new ChargingSession(
            "SESS-SHAVED-1S",
            cab1.id(),
            hub1.id(),
            charger1.id(),
            tStart,
            tEndShaved,
            47_000L,
            55_000L,
            750_000L);

    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(shavedSession));

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-10",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-10");

    ValidationReport report = validator.validate(mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H6_CHARGING_CURVE)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 11: Inflating target SoC by 1 Wh without duration increase is caught (H6)")
  void mutationInflateTargetSocBy1Wh() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tStart = t0.plusSeconds(2400);
    // Duration is 1080s (calculated for 47,000 Wh -> 55,000 Wh)
    Instant tEnd = tStart.plusSeconds(1080);

    // Target SoC inflated by 1 Wh to 55,001 Wh without increasing duration!
    ChargingSession inflatedSession =
        new ChargingSession(
            "SESS-INFLATED-1WH",
            cab1.id(),
            hub1.id(),
            charger1.id(),
            tStart,
            tEnd,
            47_000L,
            55_001L,
            750_000L);

    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(inflatedSession));

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-11",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-11");

    ValidationReport report = validator.validate(mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H6_CHARGING_CURVE)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 12: Raising claimed departure SoC after dropoff by 1 Wh is caught (D2)")
  void mutationRaiseDepartureSocAfterDropoffBy1Wh() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);
    Instant tDropoff = tPickup.plusSeconds(1200);

    // Initial 50,000 Wh - 750 Wh deadhead = 49,250 Wh arrival.
    // 49,250 Wh - 1,500 Wh trip = 47,750 Wh true dropoff SoC.
    // Claim 47,751 Wh (+1 Wh phantom energy claim)
    TripAssignment forgedAssign =
        new TripAssignment(
            baselineTrip, cab1.id(), tPickup, tDropoff, 5_000, 600, 750L, 49_250L, 47_751L);

    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(forgedAssign));

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-12",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-12");

    ValidationReport report = validator.validate(mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CLAIMED_SOC_MISMATCH)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 13: Raising session start SoC above arrival SoC at hub is caught (D2)")
  void mutationRaiseSessionStartSocAboveArrivalSocAtHub() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);
    Instant tDropoff = tPickup.plusSeconds(1200);

    TripAssignment assign =
        new TripAssignment(
            baselineTrip, cab1.id(), tPickup, tDropoff, 5_000, 600, 750L, 49_250L, 47_750L);

    Instant tChargeStart = tDropoff.plusSeconds(600);
    Instant tChargeEnd = tChargeStart.plusSeconds(1080);

    // Arrival at hub is 47,750 - 750 = 47,000 Wh.
    // Forged session claims starting at 47,001 Wh!
    ChargingSession forgedSession =
        new ChargingSession(
            "SESS-FORGED-START",
            cab1.id(),
            hub1.id(),
            charger1.id(),
            tChargeStart,
            tChargeEnd,
            47_001L,
            55_000L,
            750_000L);

    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(assign, forgedSession));

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-13",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-13");

    ValidationReport report = validator.validate(mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CLAIMED_SOC_MISMATCH)).isNotEmpty();
  }

  @Test
  @DisplayName(
      "Mutation 14: Phantom energy scenario (cab arrives with 10k Wh, claims 50k Wh start) is caught and killed (D2)")
  void mutationPhantomEnergyInjectionAtHub() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tChargeStart = t0.plusSeconds(600);
    Instant tChargeEnd = tChargeStart.plusSeconds(1080);

    // Cab starts at remote pickup location with 10,750 Wh.
    // Deadhead to hub uses 750 Wh -> arrival at hub is 10,000 Wh.
    // Session claims startSocWh = 50,000 Wh (injecting 40 kWh of phantom energy!)
    ChargingSession phantomSession =
        new ChargingSession(
            "SESS-PHANTOM",
            cab1.id(),
            hub1.id(),
            charger1.id(),
            tChargeStart,
            tChargeEnd,
            50_000L,
            55_000L,
            750_000L);

    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, pickupLoc, 10_750L, t0, List.of(phantomSession));

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-14",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-14");

    ValidationReport report = validator.validate(mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CLAIMED_SOC_MISMATCH)).isNotEmpty();
  }

  @Test
  @DisplayName(
      "Mutation 15: Raising session end SoC above curve delivery produces CLAIMED_SOC_MISMATCH (D2)")
  void mutationRaiseSessionEndSocAboveCurveDelivery() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tChargeStart = t0.plusSeconds(600);
    // 72 seconds at 50 kW charges from 47,000 Wh to 48,000 Wh
    Instant tChargeEnd = tChargeStart.plusSeconds(72);

    // Session claims ending at 49,000 Wh (1,000 Wh above the 48,000 Wh delivered by curve in 72s)
    ChargingSession inflatedEndSession =
        new ChargingSession(
            "SESS-INFLATED-END",
            cab1.id(),
            hub1.id(),
            charger1.id(),
            tChargeStart,
            tChargeEnd,
            47_000L,
            49_000L,
            750_000L);

    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, hub1Loc, 47_000L, t0, List.of(inflatedEndSession));

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-15",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-15");

    ValidationReport report = validator.validate(mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CLAIMED_SOC_MISMATCH)).isNotEmpty();
  }

  @Test
  @DisplayName(
      "Mutation 16: Scheduling session on a FAULTY charger produces CHARGER_UNAVAILABLE (D4)")
  void mutationSessionOnFaultyCharger() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tStart = t0.plusSeconds(2400);
    Instant tEnd = tStart.plusSeconds(1080);

    Charger faultyCharger = charger1.withStatus(ChargerStatus.FAULTY);
    ValidationContext faultyContext =
        ValidationContext.builder()
            .addHub(hub1)
            .addCharger(faultyCharger)
            .consumptionModel(context.consumptionModel())
            .distanceProvider(context.distanceProvider())
            .chargingCurve(context.chargingCurve())
            .batteryFloorPercentage(context.batteryFloorPercentage())
            .build();
    PlanValidator localValidator = new PlanValidator(faultyContext);

    ChargingSession session =
        new ChargingSession(
            "SESS-FAULTY",
            cab1.id(),
            hub1.id(),
            faultyCharger.id(),
            tStart,
            tEnd,
            47_000L,
            55_000L,
            750_000L);
    VehicleSchedule sched = new VehicleSchedule(cab1, hub1Loc, 47_000L, t0, List.of(session));
    FleetPlan plan =
        new FleetPlan(
            "P-FAULTY",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(sched),
            List.of(),
            1L,
            "hash");

    ValidationReport report = localValidator.validate(plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CHARGER_UNAVAILABLE)).isNotEmpty();
  }

  @Test
  @DisplayName(
      "Mutation 17: Session overlapping charger OutageWindow by 1s produces CHARGER_UNAVAILABLE (D4)")
  void mutationSessionOverlappingOutageWindowBy1Second() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tStart = t0.plusSeconds(2400);
    Instant tEnd = tStart.plusSeconds(1080);

    // Outage starts 1s before session ends: [tEnd - 1s, tEnd + 1800s)
    OutageWindow outage =
        new OutageWindow(tEnd.minusSeconds(1), tEnd.plusSeconds(1800), "Maintenance");
    Charger outageCharger = charger1.withOutages(List.of(outage));

    ValidationContext outageContext =
        ValidationContext.builder()
            .addHub(hub1)
            .addCharger(outageCharger)
            .consumptionModel(context.consumptionModel())
            .distanceProvider(context.distanceProvider())
            .chargingCurve(context.chargingCurve())
            .batteryFloorPercentage(context.batteryFloorPercentage())
            .build();
    PlanValidator localValidator = new PlanValidator(outageContext);

    ChargingSession session =
        new ChargingSession(
            "SESS-OUTAGE-OVERLAP",
            cab1.id(),
            hub1.id(),
            outageCharger.id(),
            tStart,
            tEnd,
            47_000L,
            55_000L,
            750_000L);
    VehicleSchedule sched = new VehicleSchedule(cab1, hub1Loc, 47_000L, t0, List.of(session));
    FleetPlan plan =
        new FleetPlan(
            "P-OUTAGE",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(sched),
            List.of(),
            1L,
            "hash");

    ValidationReport report = localValidator.validate(plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CHARGER_UNAVAILABLE)).isNotEmpty();
  }

  @Test
  @DisplayName(
      "Mutation 18: Session ending exactly at OutageWindow start is valid [half-open interval) (D4)")
  void mutationSessionEndingExactlyAtOutageStartIsValid() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tStart = t0.plusSeconds(2400);
    Instant tEnd = tStart.plusSeconds(1080);

    // Outage starts exactly at tEnd: [tEnd, tEnd + 1800s)
    OutageWindow outage = new OutageWindow(tEnd, tEnd.plusSeconds(1800), "Maintenance");
    Charger outageCharger = charger1.withOutages(List.of(outage));

    ValidationContext outageContext =
        ValidationContext.builder()
            .addHub(hub1)
            .addCharger(outageCharger)
            .consumptionModel(context.consumptionModel())
            .distanceProvider(context.distanceProvider())
            .chargingCurve(context.chargingCurve())
            .batteryFloorPercentage(context.batteryFloorPercentage())
            .build();
    PlanValidator localValidator = new PlanValidator(outageContext);

    ChargingSession session =
        new ChargingSession(
            "SESS-OUTAGE-EDGE",
            cab1.id(),
            hub1.id(),
            outageCharger.id(),
            tStart,
            tEnd,
            47_000L,
            55_000L,
            750_000L);
    VehicleSchedule sched = new VehicleSchedule(cab1, hub1Loc, 47_000L, t0, List.of(session));
    FleetPlan plan =
        new FleetPlan(
            "P-OUTAGE-EDGE",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(sched),
            List.of(),
            1L,
            "hash");

    ValidationReport report = localValidator.validate(plan);
    assertThat(report.violationsFor(RuleId.CHARGER_UNAVAILABLE)).isEmpty();
  }

  @Test
  @DisplayName(
      "Mutation 19: Double booking the same charger plug by 1s produces CHARGER_DOUBLE_BOOKED (D4)")
  void mutationPerChargerDoubleBookingBy1Second() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tStart1 = t0.plusSeconds(1000);
    Instant tEnd1 = tStart1.plusSeconds(1080);

    // Second session overlaps first session by 1 second on the same charger!
    Instant tStart2 = tEnd1.minusSeconds(1);
    Instant tEnd2 = tStart2.plusSeconds(1080);

    ChargingSession session1 =
        new ChargingSession(
            "SESS-PLUG-1",
            cab1.id(),
            hub1.id(),
            charger1.id(),
            tStart1,
            tEnd1,
            47_000L,
            55_000L,
            750_000L);
    ChargingSession session2 =
        new ChargingSession(
            "SESS-PLUG-2",
            cab2.id(),
            hub1.id(),
            charger1.id(),
            tStart2,
            tEnd2,
            47_000L,
            55_000L,
            750_000L);

    VehicleSchedule sched1 = new VehicleSchedule(cab1, hub1Loc, 47_000L, t0, List.of(session1));
    VehicleSchedule sched2 = new VehicleSchedule(cab2, hub1Loc, 47_000L, t0, List.of(session2));
    FleetPlan plan =
        new FleetPlan(
            "P-DOUBLE-PLUG",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(sched1, sched2),
            List.of(),
            1L,
            "hash");

    ValidationReport report = validator.validate(plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CHARGER_DOUBLE_BOOKED)).isNotEmpty();
  }

  @Test
  @DisplayName(
      "Mutation 20: Charger scheduled at hub it does not belong to produces CHARGER_UNAVAILABLE (D4)")
  void mutationChargerDoesNotBelongToHub() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tStart = t0.plusSeconds(2400);
    Instant tEnd = tStart.plusSeconds(1080);

    // Charger belongs to foreign hub
    Charger foreignCharger = Charger.of("tenant-1", "CHG-FOREIGN", "HUB-FOREIGN", 50);

    ValidationContext foreignContext =
        ValidationContext.builder()
            .addHub(hub1)
            .addCharger(foreignCharger)
            .consumptionModel(context.consumptionModel())
            .distanceProvider(context.distanceProvider())
            .chargingCurve(context.chargingCurve())
            .batteryFloorPercentage(context.batteryFloorPercentage())
            .build();
    PlanValidator localValidator = new PlanValidator(foreignContext);

    // Session is at hub1, but specifies foreignCharger
    ChargingSession session =
        new ChargingSession(
            "SESS-FOREIGN-CHG",
            cab1.id(),
            hub1.id(),
            foreignCharger.id(),
            tStart,
            tEnd,
            47_000L,
            55_000L,
            750_000L);
    VehicleSchedule sched = new VehicleSchedule(cab1, hub1Loc, 47_000L, t0, List.of(session));
    FleetPlan plan =
        new FleetPlan(
            "P-FOREIGN",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(sched),
            List.of(),
            1L,
            "hash");

    ValidationReport report = localValidator.validate(plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CHARGER_UNAVAILABLE)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 21: Omitted required trip produces TRIP_OMITTED violation (F-07)")
  void mutationOmittedRequiredTrip() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Trip extraTrip =
        new Trip(
            TripId.of("TRIP-EXTRA"),
            t0.plusSeconds(3600),
            pickupLoc,
            dropoffLoc,
            10_000,
            1200,
            t0.plusSeconds(5400),
            "EMP-MUT-EXTRA");

    ValidationInput input =
        ValidationInput.builder()
            .tenantId(TenantId.of("tenant-1"))
            .horizon(baselineValidPlan.horizonStart(), baselineValidPlan.horizonEnd())
            .consumptionModel(context.consumptionModel())
            .distanceProvider(context.distanceProvider())
            .chargingCurve(context.chargingCurve())
            .batteryFloorPercentage(context.batteryFloorPercentage())
            .enforceReserveToHub(context.enforceReserveToHub())
            .addHub(hub1)
            .addCharger(charger1)
            .addVehicle(cab1, hub1Loc, 50_000L)
            .addTrip(baselineTrip)
            .addTrip(extraTrip)
            .build();

    // Baseline plan only assigns baselineTrip, unassignedTrips is empty, so extraTrip is omitted
    ValidationReport report = validator.validate(input, baselineValidPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.TRIP_OMITTED)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 22: Unknown unassigned trip produces TRIP_UNKNOWN violation (F-07)")
  void mutationUnknownUnassignedTrip() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Trip unknownTrip =
        new Trip(
            TripId.of("TRIP-UNKNOWN-999"),
            t0.plusSeconds(3600),
            pickupLoc,
            dropoffLoc,
            10_000,
            1200,
            t0.plusSeconds(5400),
            "EMP-MUT-UNKNOWN");

    ValidationInput input =
        ValidationInput.builder()
            .tenantId(TenantId.of("tenant-1"))
            .horizon(baselineValidPlan.horizonStart(), baselineValidPlan.horizonEnd())
            .consumptionModel(context.consumptionModel())
            .distanceProvider(context.distanceProvider())
            .chargingCurve(context.chargingCurve())
            .batteryFloorPercentage(context.batteryFloorPercentage())
            .enforceReserveToHub(context.enforceReserveToHub())
            .addHub(hub1)
            .addCharger(charger1)
            .addVehicle(cab1, hub1Loc, 50_000L)
            .addTrip(baselineTrip)
            .build();

    FleetPlan planWithUnknownUnassigned =
        new FleetPlan(
            "P-UNKNOWN-UNASSIGNED",
            "tenant-1",
            baselineValidPlan.horizonStart(),
            baselineValidPlan.horizonEnd(),
            baselineValidPlan.vehicleSchedules(),
            List.of(
                UnassignedTrip.of(
                    unknownTrip, UnassignedReasonCode.NO_CAB_FREE_IN_TIME_WINDOW, "No cab")),
            999L,
            "hash");

    ValidationReport report = validator.validate(input, planWithUnknownUnassigned);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.TRIP_UNKNOWN)).isNotEmpty();
  }

  @Test
  @DisplayName(
      "Mutation 23: Trip assigned to cab and also listed in unassignedTrips produces TRIP_ASSIGNED_AND_UNASSIGNED (F-07)")
  void mutationTripAssignedAndUnassigned() {
    ValidationInput input =
        ValidationInput.builder()
            .tenantId(TenantId.of("tenant-1"))
            .horizon(baselineValidPlan.horizonStart(), baselineValidPlan.horizonEnd())
            .consumptionModel(context.consumptionModel())
            .distanceProvider(context.distanceProvider())
            .chargingCurve(context.chargingCurve())
            .batteryFloorPercentage(context.batteryFloorPercentage())
            .enforceReserveToHub(context.enforceReserveToHub())
            .addHub(hub1)
            .addCharger(charger1)
            .addVehicle(cab1, hub1Loc, 50_000L)
            .addTrip(baselineTrip)
            .build();

    FleetPlan planWithDuplicate =
        new FleetPlan(
            "P-DUPLICATE-UNASSIGNED",
            "tenant-1",
            baselineValidPlan.horizonStart(),
            baselineValidPlan.horizonEnd(),
            baselineValidPlan.vehicleSchedules(),
            List.of(
                UnassignedTrip.of(
                    baselineTrip, UnassignedReasonCode.OTHER, "Conflictingly marked unassigned")),
            999L,
            "hash");

    ValidationReport report = validator.validate(input, planWithDuplicate);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.TRIP_ASSIGNED_AND_UNASSIGNED)).isNotEmpty();
  }

  @Test
  @DisplayName(
      "Mutation 24: Charging session cost altered by 1 micro-unit produces COST_MISMATCH (F-09)")
  void mutationSessionCostAlteredByOneMicroUnit() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tStart = t0.plusSeconds(2400); // 08:40:00Z
    Instant tEnd = tStart.plusSeconds(1080); // 08:58:00Z

    // 8,000 Wh delivered (47,000 Wh -> 55,000 Wh)
    // Tariff: 100,000 micros/kWh = 0.1 / kWh -> 8,000 Wh = 800,000 micros
    UtcTariffWindow tariffWindow =
        new UtcTariffWindow(hub1.id(), t0, t0.plusSeconds(7200), 100_000L);

    ValidationInput input =
        ValidationInput.builder()
            .tenantId(TenantId.of("tenant-1"))
            .horizon(baselineValidPlan.horizonStart(), baselineValidPlan.horizonEnd())
            .consumptionModel(context.consumptionModel())
            .distanceProvider(context.distanceProvider())
            .chargingCurve(context.chargingCurve())
            .batteryFloorPercentage(context.batteryFloorPercentage())
            .enforceReserveToHub(context.enforceReserveToHub())
            .addHub(hub1)
            .addCharger(charger1)
            .addUtcTariff(tariffWindow)
            .addVehicle(cab1, hub1Loc, 50_000L)
            .addTrip(baselineTrip)
            .build();

    // Mutate session cost: expected 800,000 micros, altered by +1 micro-unit -> 800,001 micros
    ChargingSession mutatedSession =
        new ChargingSession(
            "SESS-BASE",
            cab1.id(),
            hub1.id(),
            charger1.id(),
            tStart,
            tEnd,
            47_000L,
            55_000L,
            800_001L);

    TripAssignment assign =
        new TripAssignment(
            baselineTrip,
            cab1.id(),
            baselineTrip.startTs(),
            baselineTrip.startTs().plusSeconds(1200),
            5_000,
            600,
            750L,
            49_250L,
            47_750L);
    VehicleSchedule sched =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(assign, mutatedSession));
    FleetPlan mutatedPlan =
        new FleetPlan(
            "P-COST-MUT",
            "tenant-1",
            baselineValidPlan.horizonStart(),
            baselineValidPlan.horizonEnd(),
            List.of(sched),
            List.of(),
            999L,
            "hash");

    ValidationReport report = validator.validate(input, mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.COST_MISMATCH)).isNotEmpty();
  }
}
