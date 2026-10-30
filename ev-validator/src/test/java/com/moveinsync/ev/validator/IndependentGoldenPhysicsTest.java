package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.charging.ChargingCurve;
import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.distance.MatrixDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Permanent independent golden physics tests verifying exact domain and validator arithmetic
 * without relying on self-referential production code assertions.
 */
class IndependentGoldenPhysicsTest {

  private final TenantId tenant = TenantId.of("tenant-golden");
  private final Location depot = Location.of("LOC-DEPOT", "Depot");
  private final Location pick = Location.of("LOC-PICK", "Pick");
  private final Location drop = Location.of("LOC-DROP", "Drop");
  private final Instant t0 = Instant.parse("2026-10-30T00:00:00Z");

  private static ChargingSession makeSession(
      String id,
      VehicleId cab,
      HubId hub,
      ChargerId chg,
      Instant start,
      long duration,
      long startSoc,
      long endSoc) {
    return new ChargingSession(
        id, cab, hub, chg, start, start.plusSeconds(duration), startSoc, endSoc, 1_000_000L);
  }

  @Test
  @DisplayName("Golden physics 1: Curve crossing 80% boundary requires exactly 1,201 seconds")
  void curveCrossing80PercentDurationGoldenTest() {
    long duration =
        ChargingCurve.defaultCurve().calculateDurationSeconds(50_000L, 65_001L, 75_000L, 60);
    assertThat(duration).isEqualTo(1_201L);

    Hub hub = Hub.of("tenant-golden", "HUB-1", "H1", depot, ZoneId.of("UTC"), 1);
    Charger charger = Charger.of("tenant-golden", "CHG-1", "HUB-1", 60);
    Vehicle cab = new Vehicle(tenant, VehicleId.of("CAB-01"), 75_000L, 150L, 60);
    DistanceProvider dist = new ConstantSpeedDistanceProvider(50_000, 1000);
    ValidationInput input =
        ValidationInput.builder()
            .tenantId(tenant)
            .horizon(t0, t0.plusSeconds(86400))
            .addHub(hub)
            .addCharger(charger)
            .addVehicle(cab, depot, 50_000L)
            .distanceProvider(dist)
            .consumptionModel(new ConstantConsumptionModel())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(false)
            .build();

    ChargingSession validSession =
        makeSession("S-1", cab.id(), hub.id(), charger.id(), t0, 1201, 50_000L, 65_008L);
    FleetPlan validPlan =
        new FleetPlan(
            "P-1",
            tenant.value(),
            t0,
            t0.plusSeconds(86400),
            List.of(new VehicleSchedule(cab, depot, 50_000L, t0, List.of(validSession))),
            List.of(),
            1L,
            "h");
    assertThat(new PlanValidator().validate(input, validPlan).isValid()).isTrue();

    ChargingSession shortSession =
        makeSession("S-2", cab.id(), hub.id(), charger.id(), t0, 1200, 50_000L, 65_008L);
    FleetPlan invalidPlan =
        new FleetPlan(
            "P-2",
            tenant.value(),
            t0,
            t0.plusSeconds(86400),
            List.of(new VehicleSchedule(cab, depot, 50_000L, t0, List.of(shortSession))),
            List.of(),
            1L,
            "h");
    assertThat(
            new PlanValidator()
                .validate(input, invalidPlan)
                .violationsFor(RuleId.H6_CHARGING_CURVE))
        .isNotEmpty();
  }

  @Test
  @DisplayName(
      "Golden physics 2: Battery floor rounds UP conservatively (50,001 Wh @ 15% = 7,501 Wh)")
  void batteryFloorRoundingUpGoldenTest() {
    Vehicle cab = new Vehicle(tenant, VehicleId.of("CAB-01"), 50_001L, 150L, 50);
    Hub hub = Hub.of("tenant-golden", "HUB-1", "H1", depot, ZoneId.of("UTC"), 1);
    Charger charger = Charger.of("tenant-golden", "CHG-1", "HUB-1", 50);

    Map<MatrixDistanceProvider.LocationPair, DistanceDuration> matrix =
        Map.of(
            new MatrixDistanceProvider.LocationPair(depot.id(), pick.id()),
                DistanceDuration.of(5000, 600),
            new MatrixDistanceProvider.LocationPair(pick.id(), drop.id()),
                DistanceDuration.of(1000, 120));
    DistanceProvider dist = new MatrixDistanceProvider(matrix);

    Trip trip =
        new Trip(
            tenant,
            TripId.of("T-1"),
            t0.plusSeconds(600),
            pick,
            drop,
            1000,
            120,
            t0.plusSeconds(1200),
            "EMP");
    ValidationInput input =
        ValidationInput.builder()
            .tenantId(tenant)
            .horizon(t0, t0.plusSeconds(86400))
            .addHub(hub)
            .addCharger(charger)
            .addVehicle(cab, depot, 8_401L)
            .addTrip(trip)
            .distanceProvider(dist)
            .consumptionModel(new ConstantConsumptionModel())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(false)
            .build();

    TripAssignment validAssign =
        new TripAssignment(
            trip,
            cab.id(),
            t0.plusSeconds(600),
            t0.plusSeconds(720),
            5000,
            600,
            750L,
            7_651L,
            7_501L);
    FleetPlan validPlan =
        new FleetPlan(
            "P-VALID",
            tenant.value(),
            t0,
            t0.plusSeconds(86400),
            List.of(new VehicleSchedule(cab, depot, 8_401L, t0, List.of(validAssign))),
            List.of(),
            1L,
            "h");
    assertThat(new PlanValidator().validate(input, validPlan).isValid()).isTrue();

    ValidationInput inputInvalid =
        ValidationInput.builder()
            .tenantId(tenant)
            .horizon(t0, t0.plusSeconds(86400))
            .addHub(hub)
            .addCharger(charger)
            .addVehicle(cab, depot, 8_400L)
            .addTrip(trip)
            .distanceProvider(dist)
            .consumptionModel(new ConstantConsumptionModel())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(false)
            .build();

    TripAssignment invalidAssign =
        new TripAssignment(
            trip,
            cab.id(),
            t0.plusSeconds(600),
            t0.plusSeconds(720),
            5000,
            600,
            750L,
            7_650L,
            7_500L);
    FleetPlan invalidPlan =
        new FleetPlan(
            "P-INVALID",
            tenant.value(),
            t0,
            t0.plusSeconds(86400),
            List.of(new VehicleSchedule(cab, depot, 8_400L, t0, List.of(invalidAssign))),
            List.of(),
            1L,
            "h");
    assertThat(
            new PlanValidator()
                .validate(inputInvalid, invalidPlan)
                .violationsFor(RuleId.H1_BATTERY_FLOOR))
        .isNotEmpty();
  }
}
