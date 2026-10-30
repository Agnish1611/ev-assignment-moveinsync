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
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Determinism and deduplication tests for PlanValidator (F-08).
 *
 * <p>Verifies that validation reports are strictly invariant under any permutation of input
 * collections, schedule order, and unassigned trips.
 */
class PlanValidatorDeterminismTest {

  private Location hub1Loc;
  private Location hub2Loc;
  private Location locA;
  private Location locB;

  private Hub hub1;
  private Hub hub2;
  private Charger chg1;
  private Charger chg2;

  private Vehicle cab1;
  private Vehicle cab2;
  private Vehicle cab3;

  private Trip trip1;
  private Trip trip2;
  private Trip trip3;
  private Trip trip4;

  private DistanceProvider distanceProvider;
  private PlanValidator validator;

  @BeforeEach
  void setUp() {
    hub1Loc = Location.of("LOC-HUB-1", "Hub 1");
    hub2Loc = Location.of("LOC-HUB-2", "Hub 2");
    locA = Location.of("LOC-A", "Location A");
    locB = Location.of("LOC-B", "Location B");

    hub1 = Hub.of("HUB-1", "Hub 1", hub1Loc, ZoneId.of("Asia/Kolkata"), 1);
    hub2 = Hub.of("HUB-2", "Hub 2", hub2Loc, ZoneId.of("Asia/Kolkata"), 1);
    chg1 = Charger.of("CHG-1", "HUB-1", 50);
    chg2 = Charger.of("CHG-2", "HUB-2", 50);

    cab1 = new Vehicle(VehicleId.of("CAB-01"), 60_000L, 150L, 50);
    cab2 = new Vehicle(VehicleId.of("CAB-02"), 60_000L, 150L, 50);
    cab3 = new Vehicle(VehicleId.of("CAB-03"), 60_000L, 150L, 50);

    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    trip1 =
        new Trip(
            TripId.of("T1"),
            t0.plusSeconds(600),
            locA,
            locB,
            10_000,
            1200,
            t0.plusSeconds(3600),
            "E1");
    trip2 =
        new Trip(
            TripId.of("T2"),
            t0.plusSeconds(1800),
            locA,
            locB,
            10_000,
            1200,
            t0.plusSeconds(4000),
            "E2");
    trip3 =
        new Trip(
            TripId.of("T3"),
            t0.plusSeconds(3000),
            locA,
            locB,
            10_000,
            1200,
            t0.plusSeconds(6000),
            "E3");
    trip4 =
        new Trip(
            TripId.of("T4"),
            t0.plusSeconds(4200),
            locA,
            locB,
            10_000,
            1200,
            t0.plusSeconds(7000),
            "E4");

    Map<MatrixDistanceProvider.LocationPair, DistanceDuration> matrix =
        Map.of(
            new MatrixDistanceProvider.LocationPair(hub1Loc.id(), locA.id()),
                DistanceDuration.of(5_000, 600),
            new MatrixDistanceProvider.LocationPair(locA.id(), locB.id()),
                DistanceDuration.of(10_000, 1200),
            new MatrixDistanceProvider.LocationPair(locB.id(), hub1Loc.id()),
                DistanceDuration.of(5_000, 600),
            new MatrixDistanceProvider.LocationPair(hub2Loc.id(), locA.id()),
                DistanceDuration.of(5_000, 600),
            new MatrixDistanceProvider.LocationPair(locB.id(), hub2Loc.id()),
                DistanceDuration.of(5_000, 600));
    distanceProvider =
        new MatrixDistanceProvider(matrix, new ConstantSpeedDistanceProvider(5_000, 10));

    validator = new PlanValidator();
  }

  @Test
  @DisplayName("Validation report is strictly identical across 200 random permutations (F-08)")
  void reportStrictlyDeterministicAcross200Permutations() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tEnd = t0.plusSeconds(14400);

    // Schedule 1: Cab1 with Trip1 (late pickup -> H3) and a charging session
    TripAssignment assign1 =
        new TripAssignment(
            trip1,
            cab1.id(),
            trip1.startTs().plusSeconds(10),
            trip1.startTs().plusSeconds(1210),
            5_000,
            600,
            750L,
            49_250L,
            47_750L);
    ChargingSession sess1 =
        new ChargingSession(
            "S1",
            cab1.id(),
            hub1.id(),
            chg1.id(),
            t0.plusSeconds(2400),
            t0.plusSeconds(3600),
            47_000L,
            55_000L,
            100_000L);
    VehicleSchedule sched1 =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(assign1, sess1));

    // Schedule 2: Cab2 with Trip2 (late pickup -> H3) and concurrent charging session on Hub1
    // (causing H2 violation:
    // capacity=1, sessions=2)
    TripAssignment assign2 =
        new TripAssignment(
            trip2,
            cab2.id(),
            trip2.startTs().plusSeconds(20),
            trip2.startTs().plusSeconds(1220),
            5_000,
            600,
            750L,
            49_250L,
            47_750L);
    ChargingSession sess2 =
        new ChargingSession(
            "S2",
            cab2.id(),
            hub1.id(),
            chg1.id(),
            t0.plusSeconds(2500),
            t0.plusSeconds(3500),
            47_000L,
            55_000L,
            100_000L);
    VehicleSchedule sched2 =
        new VehicleSchedule(cab2, hub1Loc, 50_000L, t0, List.of(assign2, sess2));

    // Schedule 3: Cab3 with intentional H3 violation (pickup 100 seconds late)
    TripAssignment assign3Late =
        new TripAssignment(
            trip3,
            cab3.id(),
            trip3.startTs().plusSeconds(100),
            trip3.startTs().plusSeconds(1300),
            5_000,
            600,
            750L,
            49_250L,
            47_750L);
    VehicleSchedule sched3 = new VehicleSchedule(cab3, hub1Loc, 50_000L, t0, List.of(assign3Late));

    // Unassigned trip: trip4
    UnassignedTrip unassigned4 =
        UnassignedTrip.of(trip4, UnassignedReasonCode.CHARGER_CONGESTION, "No plug available");

    // Baseline validation input
    ValidationInput baselineInput =
        ValidationInput.builder()
            .tenantId(TenantId.of("tenant-1"))
            .horizon(t0, tEnd)
            .consumptionModel(new ConstantConsumptionModel())
            .distanceProvider(distanceProvider)
            .chargingCurve(ChargingCurve.defaultCurve())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(true)
            .addHub(hub1)
            .addHub(hub2)
            .addCharger(chg1)
            .addCharger(chg2)
            .addVehicle(cab1, hub1Loc, 50_000L)
            .addVehicle(cab2, hub1Loc, 50_000L)
            .addVehicle(cab3, hub1Loc, 50_000L)
            .addTrip(trip1)
            .addTrip(trip2)
            .addTrip(trip3)
            .addTrip(trip4)
            .build();

    FleetPlan baselinePlan =
        new FleetPlan(
            "PLAN-BASE",
            "tenant-1",
            t0,
            tEnd,
            List.of(sched1, sched2, sched3),
            List.of(unassigned4),
            42L,
            "hash-base");

    ValidationReport baselineReport = validator.validate(baselineInput, baselinePlan);
    assertThat(baselineReport.isValid()).isFalse();
    assertThat(baselineReport.violations()).isNotEmpty();

    int expectedViolationCount = baselineReport.violations().size();
    String baselineSerialized = baselineReport.violations().toString();

    // Permute across 200 random iterations
    Random rng = new Random(42);
    for (int i = 0; i < 200; i++) {
      // Permute vehicle schedules
      List<VehicleSchedule> permutedSchedules = new ArrayList<>(List.of(sched1, sched2, sched3));
      Collections.shuffle(permutedSchedules, rng);

      // Permute unassigned trips
      List<UnassignedTrip> permutedUnassigned = new ArrayList<>(List.of(unassigned4));
      Collections.shuffle(permutedUnassigned, rng);

      FleetPlan permutedPlan =
          new FleetPlan(
              "PLAN-PERMUTED-" + i,
              "tenant-1",
              t0,
              tEnd,
              permutedSchedules,
              permutedUnassigned,
              42L,
              "hash-base");

      // Permute input collections
      List<Hub> permutedHubs = new ArrayList<>(List.of(hub1, hub2));
      Collections.shuffle(permutedHubs, rng);

      List<Charger> permutedChargers = new ArrayList<>(List.of(chg1, chg2));
      Collections.shuffle(permutedChargers, rng);

      List<Trip> permutedTrips = new ArrayList<>(List.of(trip1, trip2, trip3, trip4));
      Collections.shuffle(permutedTrips, rng);

      ValidationInput.Builder inputBuilder =
          ValidationInput.builder()
              .tenantId(TenantId.of("tenant-1"))
              .horizon(t0, tEnd)
              .consumptionModel(new ConstantConsumptionModel())
              .distanceProvider(distanceProvider)
              .chargingCurve(ChargingCurve.defaultCurve())
              .batteryFloorPercentage(15)
              .enforceReserveToHub(true);

      permutedHubs.forEach(inputBuilder::addHub);
      permutedChargers.forEach(inputBuilder::addCharger);
      permutedTrips.forEach(inputBuilder::addTrip);
      inputBuilder.addVehicle(cab1, hub1Loc, 50_000L);
      inputBuilder.addVehicle(cab2, hub1Loc, 50_000L);
      inputBuilder.addVehicle(cab3, hub1Loc, 50_000L);

      ValidationReport permutedReport = validator.validate(inputBuilder.build(), permutedPlan);

      assertThat(permutedReport.isValid()).isEqualTo(baselineReport.isValid());
      assertThat(permutedReport.violations().size()).isEqualTo(expectedViolationCount);
      assertThat(permutedReport.violations())
          .as(
              "Violations must match baseline element-for-element and in exact order at iteration %d",
              i)
          .isEqualTo(baselineReport.violations());
      assertThat(permutedReport.violations().toString())
          .as("String serialization must be byte-for-byte identical at iteration %d", i)
          .isEqualTo(baselineSerialized);
    }
  }
}
