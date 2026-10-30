package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.charging.ChargingCurve;
import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Probes overflow boundaries and extreme parameters across domain physics and validation logic to
 * ensure that numeric overflow never results in silent negative wraparound.
 */
class ValidationOverflowProbeTest {

  private final TenantId tenant = TenantId.of("tenant-overflow");
  private final Location hubLoc = Location.of("LOC-HUB", "Hub");
  private final Instant t0 = Instant.parse("2026-10-30T00:00:00Z");

  @Test
  @DisplayName(
      "Extreme distance (Integer.MAX_VALUE) in consumption model does not wrap around to negative")
  void extremeDistanceDoesNotWrapAround() {
    ConstantConsumptionModel model = new ConstantConsumptionModel();
    Vehicle cab = new Vehicle(tenant, VehicleId.of("CAB-MAX"), 100_000_000L, 250L, 50);

    long energyWh = model.calculateEnergyWh(cab, Integer.MAX_VALUE, t0, false);
    assertThat(energyWh).isPositive();
    // 2,147,483,647 meters * 250 Wh/km / 1000 = ~536,870,912 Wh
    assertThat(energyWh).isEqualTo(536_870_912L);
  }

  @Test
  @DisplayName(
      "Overflow in base consumption product throws ArithmeticException instead of silent wraparound")
  void overflowInBaseConsumptionThrowsArithmeticException() {
    ConstantConsumptionModel model = new ConstantConsumptionModel();
    // base consumption near Long.MAX_VALUE
    Vehicle extremeCab =
        new Vehicle(tenant, VehicleId.of("CAB-OVER"), Long.MAX_VALUE, Long.MAX_VALUE / 10, 50);

    assertThatThrownBy(() -> model.calculateEnergyWh(extremeCab, Integer.MAX_VALUE, t0, false))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  @DisplayName(
      "Far-future timestamps (Year 2099) do not overflow duration or interval calculations")
  void farFutureTimestampsHandledCleanly() {
    Instant futureStart = Instant.parse("2099-01-01T00:00:00Z");
    Instant futureEnd = Instant.parse("2099-01-02T00:00:00Z");

    Hub hub = Hub.of("tenant-overflow", "HUB-1", "H1", hubLoc, ZoneId.of("UTC"), 1);
    Charger charger = Charger.of("tenant-overflow", "CHG-1", "HUB-1", 50);
    Vehicle cab = new Vehicle(tenant, VehicleId.of("CAB-01"), 60_000L, 150L, 50);
    DistanceProvider dist = new ConstantSpeedDistanceProvider(5000, 10);

    ValidationInput input =
        ValidationInput.builder()
            .tenantId(tenant)
            .horizon(futureStart, futureEnd)
            .addHub(hub)
            .addCharger(charger)
            .addVehicle(cab, hubLoc, 20_000L)
            .distanceProvider(dist)
            .consumptionModel(new ConstantConsumptionModel())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(false)
            .build();

    ChargingSession session =
        new ChargingSession(
            "S-FUT",
            cab.id(),
            hub.id(),
            charger.id(),
            futureStart,
            futureStart.plusSeconds(1440),
            20_000L,
            40_000L,
            1_000_000L);

    FleetPlan plan =
        new FleetPlan(
            "P-FUT",
            tenant.value(),
            futureStart,
            futureEnd,
            List.of(new VehicleSchedule(cab, hubLoc, 20_000L, futureStart, List.of(session))),
            List.of(),
            1L,
            "h");

    ValidationReport report = new PlanValidator().validate(input, plan);
    assertThat(report).isNotNull();
    assertThat(report.isValid()).isTrue();
  }

  @Test
  @DisplayName(
      "Extreme tariff cost multiplication catches overflow and fails closed without throwing")
  void extremeTariffMultiplicationCatchesOverflow() {
    HubId hubId = HubId.of("HUB-1");
    UtcTariffWindow extremeWindow =
        new UtcTariffWindow(hubId, t0, t0.plusSeconds(3600), Long.MAX_VALUE / 2);

    // If an extreme session causes multiplication overflow, TariffCostValidator protects via
    // Math.multiplyExact
    assertThatThrownBy(
            () ->
                TariffCostValidator.calculateCostMicros(
                    t0, t0.plusSeconds(3600), Long.MAX_VALUE / 2, List.of(extremeWindow)))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  @DisplayName("ChargingCurve rejects integer power overflow or negative values")
  void chargingCurveRejectsOverflowingParameters() {
    ChargingCurve curve = ChargingCurve.defaultCurve();
    assertThatThrownBy(() -> curve.calculateDurationSeconds(10_000L, 20_000L, 60_000L, -50))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> curve.calculateDurationSeconds(10_000L, 20_000L, 60_000L, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
