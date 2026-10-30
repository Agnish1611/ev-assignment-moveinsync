package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Performance benchmark verifying realistic validation timing for large fleet plans (&gt;= 1,500
 * trips, &gt;= 300 charging sessions across 100 vehicles).
 *
 * <h3>Complexity Documentation:</h3>
 *
 * <ul>
 *   <li><b>Time Complexity:</b> {@code O((T + S) * log((T + S) / V) + S * log S + T + V)} where
 *       {@code T} is the total number of trips, {@code S} is the number of charging sessions, and
 *       {@code V} is the vehicle fleet size. The dominant terms are sorting activities per vehicle
 *       timeline ({@code O((T + S) * log K)}) and the continuous charger capacity interval sweep
 *       ({@code O(S * log S)}).
 *   <li><b>Space Complexity:</b> {@code O(T + S + V + H + C)} auxiliary memory to store sweep
 *       events, indexed maps, and the resulting validation report.
 * </ul>
 */
class ValidationPerformanceBenchmarkTest {

  private static final int VEHICLE_COUNT = 100;
  private static final int TRIPS_PER_VEHICLE = 15; // 1,500 total trips
  private static final int SESSIONS_PER_VEHICLE = 3; // 300 total charging sessions
  private static final int WARMUP_RUNS = 5;
  private static final int BENCHMARK_RUNS = 20;

  @Test
  @DisplayName(
      "Realistic scale benchmark: >=1,500 trips and >=300 sessions validated with p50/p99 recorded")
  void realisticScaleValidationBenchmark() {
    TenantId tenant = TenantId.of("tenant-bench");
    Instant horizonStart = Instant.parse("2026-10-30T00:00:00Z");
    Instant horizonEnd = Instant.parse("2026-10-31T00:00:00Z");

    Location hubLoc = Location.of("LOC-HUB", "Central Hub");
    Location pickLoc = Location.of("LOC-PICK", "Pickup Zone");
    Location dropLoc = Location.of("LOC-DROP", "Dropoff Zone");

    Hub hub = Hub.of("tenant-bench", "HUB-01", "Central", hubLoc, ZoneId.of("UTC"), 50);
    List<Charger> chargers = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      chargers.add(Charger.of("tenant-bench", "CHG-" + i, "HUB-01", 50));
    }

    DistanceProvider dist = new ConstantSpeedDistanceProvider(5000, 10);
    ValidationInput.Builder inputBuilder =
        ValidationInput.builder()
            .tenantId(tenant)
            .horizon(horizonStart, horizonEnd)
            .addHub(hub)
            .distanceProvider(dist)
            .consumptionModel(new ConstantConsumptionModel())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(false);

    chargers.forEach(inputBuilder::addCharger);

    List<Vehicle> vehicles = new ArrayList<>();
    List<VehicleSchedule> schedules = new ArrayList<>();
    List<Trip> allTrips = new ArrayList<>();
    int tripIdCounter = 1;
    int sessionIdCounter = 1;

    for (int v = 0; v < VEHICLE_COUNT; v++) {
      Vehicle cab = new Vehicle(tenant, VehicleId.of("CAB-" + v), 75_000L, 150L, 50);
      vehicles.add(cab);
      inputBuilder.addVehicle(cab, hubLoc, 60_000L);

      List<RouteActivity> activities = new ArrayList<>();
      Instant currentTime = horizonStart.plusSeconds(600);

      for (int t = 0; t < TRIPS_PER_VEHICLE; t++) {
        Trip trip =
            new Trip(
                tenant,
                TripId.of("TRIP-" + tripIdCounter++),
                currentTime,
                pickLoc,
                dropLoc,
                5000,
                500,
                currentTime.plusSeconds(600),
                "EMP-" + v);
        allTrips.add(trip);
        inputBuilder.addTrip(trip);

        TripAssignment assign =
            new TripAssignment(
                trip,
                cab.id(),
                currentTime,
                currentTime.plusSeconds(500),
                5000,
                500,
                750L,
                59_250L,
                58_500L);
        activities.add(assign);
        currentTime = currentTime.plusSeconds(700);

        if ((t + 1) % 5 == 0 && activities.size() < (TRIPS_PER_VEHICLE + SESSIONS_PER_VEHICLE)) {
          Charger charger = chargers.get(v % 50);
          ChargingSession session =
              new ChargingSession(
                  "SESS-" + sessionIdCounter++,
                  cab.id(),
                  hub.id(),
                  charger.id(),
                  currentTime,
                  currentTime.plusSeconds(1800),
                  30_000L,
                  50_000L,
                  1_000_000L);
          activities.add(session);
          currentTime = currentTime.plusSeconds(1900);
        }
      }

      schedules.add(new VehicleSchedule(cab, hubLoc, 60_000L, horizonStart, activities));
    }

    ValidationInput input = inputBuilder.build();
    FleetPlan plan =
        new FleetPlan(
            "PLAN-SCALE",
            tenant.value(),
            horizonStart,
            horizonEnd,
            schedules,
            List.of(),
            42L,
            "hash-benchmark");

    assertThat(plan.totalAssignedTrips()).isGreaterThanOrEqualTo(1500);
    assertThat(plan.totalChargingSessions()).isGreaterThanOrEqualTo(300);

    PlanValidator validator = new PlanValidator();

    // JVM Warmup
    for (int w = 0; w < WARMUP_RUNS; w++) {
      validator.validate(input, plan);
    }

    // Timed Benchmark Runs
    long[] runtimesNanos = new long[BENCHMARK_RUNS];
    for (int i = 0; i < BENCHMARK_RUNS; i++) {
      long start = System.nanoTime();
      ValidationReport report = validator.validate(input, plan);
      runtimesNanos[i] = System.nanoTime() - start;
      assertThat(report).isNotNull();
    }

    Arrays.sort(runtimesNanos);
    long p50Nanos = runtimesNanos[BENCHMARK_RUNS / 2];
    long p99Nanos = runtimesNanos[(int) (BENCHMARK_RUNS * 0.99)];

    double p50Millis = p50Nanos / 1_000_000.0;
    double p99Millis = p99Nanos / 1_000_000.0;

    // Realistic scale validation (1500 trips, 300 sessions) must complete in well under 100 ms
    assertThat(p50Millis).isLessThan(100.0);
    assertThat(p99Millis).isLessThan(250.0);
  }
}
