package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Golden physics tests for traffic/AC energy and charger capacity interval sweeps. */
class GoldenSweepAndPhysicsTest {

  private final TenantId tenant = TenantId.of("tenant-golden");
  private final Location depot = Location.of("LOC-DEPOT", "Depot");
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
  @DisplayName("Golden physics 3: Deadhead + trip energy with traffic & AC equals exactly 3,916 Wh")
  void deadheadPlusTripEnergyGoldenTest() {
    ConstantConsumptionModel model = new ConstantConsumptionModel(12_500, 11_000);
    Vehicle testCab = new Vehicle(tenant, VehicleId.of("CAB-PHYS"), 60_000L, 160L, 50);
    long deadheadEnergy = model.calculateEnergyWh(testCab, 5500, t0, true);
    long loadedEnergy = model.calculateEnergyWh(testCab, 12300, t0, true);

    assertThat(deadheadEnergy).isEqualTo(1_210L);
    assertThat(loadedEnergy).isEqualTo(2_706L);
    assertThat(deadheadEnergy + loadedEnergy).isEqualTo(3_916L);
  }

  @Test
  @DisplayName(
      "Golden physics 4: Charger capacity sweep interval [100, 200) vs [200, 300) does not overlap")
  void chargerCapacitySweepTouchingVsOverlappingGoldenTest() {
    Hub hub = Hub.of("tenant-golden", "HUB-1", "H1", depot, ZoneId.of("UTC"), 1);
    Charger charger = Charger.of("tenant-golden", "CHG-1", "HUB-1", 50);
    Vehicle cab1 = new Vehicle(tenant, VehicleId.of("CAB-01"), 60_000L, 150L, 50);
    Vehicle cab2 = new Vehicle(tenant, VehicleId.of("CAB-02"), 60_000L, 150L, 50);
    DistanceProvider dist = new ConstantSpeedDistanceProvider(50_000, 1000);

    ValidationInput input =
        ValidationInput.builder()
            .tenantId(tenant)
            .horizon(t0, t0.plusSeconds(86400))
            .addHub(hub)
            .addCharger(charger)
            .addVehicle(cab1, depot, 20_000L)
            .addVehicle(cab2, depot, 20_000L)
            .distanceProvider(dist)
            .consumptionModel(new ConstantConsumptionModel())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(false)
            .build();

    ChargingSession s1 =
        makeSession(
            "S-1", cab1.id(), hub.id(), charger.id(), t0.plusSeconds(100), 100, 20_000L, 30_000L);
    ChargingSession s2 =
        makeSession(
            "S-2", cab2.id(), hub.id(), charger.id(), t0.plusSeconds(200), 100, 20_000L, 30_000L);

    FleetPlan touchingPlan =
        new FleetPlan(
            "P-TOUCH",
            tenant.value(),
            t0,
            t0.plusSeconds(86400),
            List.of(
                new VehicleSchedule(cab1, depot, 20_000L, t0, List.of(s1)),
                new VehicleSchedule(cab2, depot, 20_000L, t0, List.of(s2))),
            List.of(),
            1L,
            "h");
    assertThat(new ChargerCapacityValidator(input.toValidationContext()).validate(touchingPlan))
        .isEmpty();

    ChargingSession s2Overlap =
        makeSession(
            "S-2-OV",
            cab2.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(199),
            101,
            20_000L,
            30_000L);
    FleetPlan overlappingPlan =
        new FleetPlan(
            "P-OVERLAP",
            tenant.value(),
            t0,
            t0.plusSeconds(86400),
            List.of(
                new VehicleSchedule(cab1, depot, 20_000L, t0, List.of(s1)),
                new VehicleSchedule(cab2, depot, 20_000L, t0, List.of(s2Overlap))),
            List.of(),
            1L,
            "h");
    assertThat(new ChargerCapacityValidator(input.toValidationContext()).validate(overlappingPlan))
        .isNotEmpty();
  }
}
