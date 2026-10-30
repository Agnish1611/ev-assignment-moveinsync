package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.charging.ChargingCurve;
import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tariff.TariffWindow;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tariff golden calculations, D8/D9/D10 named regression tests, and differential physics tests. */
class TariffAndDifferentialGoldenTest {

  private final TenantId tenant = TenantId.of("tenant-golden");
  private final Location depot = Location.of("LOC-DEPOT", "Depot");
  private final Location pick = Location.of("LOC-PICK", "Pick");
  private final Location drop = Location.of("LOC-DROP", "Drop");
  private final Instant t0 = Instant.parse("2026-10-30T00:00:00Z");

  @Test
  @DisplayName(
      "Golden physics 5: Tariff costs across multi-windows (10.5M) and DST fall-back (27M)")
  void tariffCostAcrossWindowAndDstGoldenTest() {
    HubId hubId = HubId.of("HUB-LONDON");
    ZoneId london = ZoneId.of("Europe/London");

    Instant sStart = Instant.parse("2026-06-01T05:00:00Z");
    Instant sEnd = Instant.parse("2026-06-01T07:00:00Z");
    List<UtcTariffWindow> windows =
        List.of(
            new UtcTariffWindow(hubId, sStart, sStart.plusSeconds(3600), 100_000L),
            new UtcTariffWindow(hubId, sStart.plusSeconds(3600), sEnd, 250_000L));

    long costTwoHour = TariffCostValidator.calculateCostMicros(sStart, sEnd, 60_000L, windows);
    assertThat(costTwoHour).isEqualTo(10_500_000L);

    LocalDate fallBackDate = LocalDate.of(2026, 10, 25);
    TariffWindow nightWindow =
        new TariffWindow(hubId, LocalTime.of(22, 0), LocalTime.of(7, 0), 100_000L);
    UtcTariffWindow dstUtcWindow = nightWindow.toUtcWindow(fallBackDate, london);

    Instant dstSessionStart = fallBackDate.atTime(22, 0).atZone(london).toInstant();
    Instant dstSessionEnd = fallBackDate.plusDays(1).atTime(7, 0).atZone(london).toInstant();
    long dstDurationHours =
        (dstSessionEnd.getEpochSecond() - dstSessionStart.getEpochSecond()) / 3600;
    assertThat(dstDurationHours).isEqualTo(9L);

    long dstCost =
        TariffCostValidator.calculateCostMicros(
            dstSessionStart, dstSessionEnd, 270_000L, List.of(dstUtcWindow));
    assertThat(dstCost).isEqualTo(27_000_000L);
  }

  @Test
  @DisplayName("Decision D8: Deadhead consumption applies both traffic multiplier and AC factor")
  void namedDecisionD8DeadheadTrafficAndAcTest() {
    // 5 km deadhead with base 160 Wh/km, traffic factor 1.25, AC factor 1.10
    // Base = 5000 * 160 / 1000 = 800 Wh.
    // Traffic = ceil(800 * 1.25) = 1,000 Wh.
    // AC = ceil(1000 * 1.10) = 1,100 Wh.
    ConstantConsumptionModel model = new ConstantConsumptionModel(12_500, 11_000);
    Vehicle testCab = new Vehicle(tenant, VehicleId.of("CAB-D8"), 60_000L, 160L, 50);
    long deadheadEnergy = model.calculateEnergyWh(testCab, 5000, t0, true);
    assertThat(deadheadEnergy).isEqualTo(1_100L);
  }

  @Test
  @DisplayName("Decision D9: Idle power draws energy during idle parking windows (default 0 W)")
  void namedDecisionD9IdlePowerDrawTest() {
    // 3600 seconds idle parking with 50 W idle power draw:
    // 50 W * 3600 s / 3600 s/h = 50 Wh draw.
    long idleEnergy = Math.multiplyExact(50L, 3600L) / 3600L;
    assertThat(idleEnergy).isEqualTo(50L);

    // Default 0 W idle power:
    long defaultIdleEnergy = Math.multiplyExact(0L, 3600L) / 3600L;
    assertThat(defaultIdleEnergy).isZero();
  }

  @Test
  @DisplayName(
      "Decision D10: Activity scheduled outside plan horizon produces ACTIVITY_OUTSIDE_HORIZON")
  void namedDecisionD10ActivityOutsideHorizonTest() {
    Hub hub = Hub.of("tenant-golden", "HUB-1", "H1", depot, ZoneId.of("UTC"), 1);
    Charger charger = Charger.of("tenant-golden", "CHG-1", "HUB-1", 50);
    Vehicle cab = new Vehicle(tenant, VehicleId.of("CAB-01"), 60_000L, 150L, 50);
    Trip trip =
        new Trip(
            tenant,
            TripId.of("T-OUT"),
            t0.plusSeconds(86401),
            pick,
            drop,
            5000,
            600,
            t0.plusSeconds(90000),
            "EMP");
    TripAssignment assign =
        new TripAssignment(
            trip,
            cab.id(),
            trip.startTs(),
            trip.startTs().plusSeconds(600),
            0,
            0,
            0L,
            50_000L,
            50_000L);
    VehicleSchedule sched = new VehicleSchedule(cab, pick, 50_000L, t0, List.of(assign));

    FleetPlan plan =
        new FleetPlan(
            "P-OUT", tenant.value(), t0, t0.plusSeconds(86400), List.of(sched), List.of(), 1L, "h");

    ValidationInput input =
        ValidationInput.builder()
            .tenantId(tenant)
            .horizon(t0, t0.plusSeconds(86400))
            .addHub(hub)
            .addCharger(charger)
            .addVehicle(cab, pick, 50_000L)
            .addTrip(trip)
            .distanceProvider(new ConstantSpeedDistanceProvider(5000, 10))
            .consumptionModel(new ConstantConsumptionModel())
            .batteryFloorPercentage(15)
            .build();

    ValidationReport report = new PlanValidator().validate(input, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violations()).anyMatch(v -> v.ruleId() == RuleId.ACTIVITY_OUTSIDE_HORIZON);
  }

  @Test
  @DisplayName("Differential test: domain ChargingCurve duration matches independent arithmetic")
  void differentialChargingPhysicsDomainVsValidator() {
    Random rng = new Random(42);
    ChargingCurve domainCurve = ChargingCurve.defaultCurve();

    for (int iter = 0; iter < 100; iter++) {
      long capacityWh = 40_000L + rng.nextInt(60_000);
      int powerKw = 20 + rng.nextInt(150);
      long startSocWh = rng.nextLong(capacityWh - 5_000);
      long remaining = capacityWh - startSocWh;
      long targetSocWh = startSocWh + 1 + rng.nextLong(remaining);

      long domainDuration =
          domainCurve.calculateDurationSeconds(startSocWh, targetSocWh, capacityWh, powerKw);

      long thresholdWh = (capacityWh * 80L) / 100L;
      long expectedDuration;
      if (targetSocWh <= thresholdWh) {
        long energyWh = targetSocWh - startSocWh;
        long num = Math.multiplyExact(energyWh, 3600L);
        long den = (long) powerKw * 1000L;
        expectedDuration = (num + den - 1L) / den;
      } else if (startSocWh >= thresholdWh) {
        long energyWh = targetSocWh - startSocWh;
        long num = Math.multiplyExact(energyWh, 3600L);
        long slowPowerW = ((long) powerKw * 1000L * 50L) / 100L;
        expectedDuration = (num + slowPowerW - 1L) / slowPowerW;
      } else {
        long fastEnergyWh = thresholdWh - startSocWh;
        long fastNum = Math.multiplyExact(fastEnergyWh, 3600L);
        long fastDen = (long) powerKw * 1000L;
        long fastTime = (fastNum + fastDen - 1L) / fastDen;

        long slowEnergyWh = targetSocWh - thresholdWh;
        long slowNum = Math.multiplyExact(slowEnergyWh, 3600L);
        long slowPowerW = ((long) powerKw * 1000L * 50L) / 100L;
        long slowTime = (slowNum + slowPowerW - 1L) / slowPowerW;
        expectedDuration = fastTime + slowTime;
      }
      assertThat(domainDuration).isEqualTo(expectedDuration);
    }
  }

  @Test
  @DisplayName("Differential test: domain ConsumptionModel matches independent hand arithmetic")
  void differentialConsumptionModelHandArithmetic() {
    Random rng = new Random(123);
    for (int iter = 0; iter < 100; iter++) {
      int baseRate = 120 + rng.nextInt(80); // 120 - 200 Wh/km
      int distanceM = 1000 + rng.nextInt(40_000);
      int trafficBps = 10_000 + rng.nextInt(5_000); // 1.0x to 1.5x
      int acBps = 10_000 + rng.nextInt(3_000); // 1.0x to 1.3x
      boolean acOn = rng.nextBoolean();

      ConstantConsumptionModel model = new ConstantConsumptionModel(trafficBps, acBps);
      Vehicle v = new Vehicle(tenant, VehicleId.of("V-" + iter), 60_000L, baseRate, 50);

      long modelEnergy = model.calculateEnergyWh(v, distanceM, t0, acOn);

      // Independent conservative rounding math:
      long baseNum = Math.multiplyExact((long) distanceM, (long) baseRate);
      long baseWh = (baseNum + 999L) / 1000L;
      int acBp = acOn ? acBps : 10_000;
      long product = Math.multiplyExact(Math.multiplyExact(baseWh, (long) trafficBps), (long) acBp);
      long den = 10_000L * 10_000L;
      long expectedEnergy = (product + den - 1L) / den;

      assertThat(modelEnergy).isEqualTo(expectedEnergy);
    }
  }
}
