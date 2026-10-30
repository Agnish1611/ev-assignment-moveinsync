package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

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

  @Test
  @DisplayName("Golden physics 1: Curve crossing 80% boundary requires exactly 1,201 seconds")
  void curveCrossing80PercentDurationGoldenTest() {
    // Battery: 75,000 Wh, 80% threshold = 60,000 Wh, Charger power = 60 kW (60,000 W)
    // Start: 50,000 Wh, Target: 65,001 Wh
    // Fast segment: 10,000 Wh @ 60 kW -> ceil(10,000 * 3600 / 60,000) = 600 s
    // Slow segment: 5,001 Wh @ 30 kW -> ceil(5,001 * 3600 / 30,000) = ceil(600.12) = 601 s
    // Golden total duration = 600 + 601 = 1,201 seconds
    long duration =
        ChargingCurve.defaultCurve().calculateDurationSeconds(50_000L, 65_001L, 75_000L, 60);
    assertThat(duration).isEqualTo(1_201L);

    // Verify validator accepts exact 1,201s session and rejects 1,200s session
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
        new ChargingSession(
            "S-1",
            cab.id(),
            hub.id(),
            charger.id(),
            t0,
            t0.plusSeconds(1201),
            50_000L,
            65_001L,
            1_000_000L);
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
    ValidationReport report = new PlanValidator().validate(input, validPlan);
    if (!report.isValid()) {
      org.junit.jupiter.api.Assertions.fail(
          "Valid plan failed with violations: " + report.violations());
    }

    // 1,200 seconds is 1s short -> must produce H6_CHARGING_CURVE violation
    ChargingSession shortSession =
        new ChargingSession(
            "S-2",
            cab.id(),
            hub.id(),
            charger.id(),
            t0,
            t0.plusSeconds(1200),
            50_000L,
            65_001L,
            1_000_000L);
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
    // Battery capacity = 50,001 Wh. 15% floor = 50,001 * 0.15 = 7,500.15 Wh.
    // Integer conservative ceiling: ceil(50,001 * 15 / 100) = (50,001 * 15 + 99) / 100 = 7,501 Wh.
    Vehicle cab = new Vehicle(tenant, VehicleId.of("CAB-01"), 50_001L, 150L, 50);
    Hub hub = Hub.of("tenant-golden", "HUB-1", "H1", depot, ZoneId.of("UTC"), 1);
    Charger charger = Charger.of("tenant-golden", "CHG-1", "HUB-1", 50);

    java.util.Map<
            com.moveinsync.ev.domain.distance.MatrixDistanceProvider.LocationPair,
            com.moveinsync.ev.domain.distance.DistanceDuration>
        matrix = new java.util.HashMap<>();
    matrix.put(
        new com.moveinsync.ev.domain.distance.MatrixDistanceProvider.LocationPair(
            depot.id(), pick.id()),
        new com.moveinsync.ev.domain.distance.DistanceDuration(5000, 600));
    matrix.put(
        new com.moveinsync.ev.domain.distance.MatrixDistanceProvider.LocationPair(
            pick.id(), drop.id()),
        new com.moveinsync.ev.domain.distance.DistanceDuration(1000, 120));
    DistanceProvider dist = new com.moveinsync.ev.domain.distance.MatrixDistanceProvider(matrix);

    // Initial SoC = 8,401 Wh.
    // Deadhead: 5,000m @ 150 Wh/km = 750 Wh -> Arrival at pickup = 7,651 Wh.
    // Loaded trip: 1,000m @ 150 Wh/km = 150 Wh -> Arrival at dropoff = 7,501 Wh (== 7,501 Wh
    // floor).
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

    // Initial SoC = 8,400 Wh -> Arrival at dropoff = 7,500 Wh (< 7,501 Wh floor) -> H1 violation
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

  @Test
  @DisplayName("Golden physics 3: Deadhead + trip energy with traffic & AC equals exactly 3,916 Wh")
  void deadheadPlusTripEnergyGoldenTest() {
    // Base: 160 Wh/km. Traffic: x1.25. AC: x1.10.
    // Deadhead (5,500 m):
    //   base = 5,500 * 160 / 1000 = 880 Wh
    //   traffic = ceil(880 * 125 / 100) = 1,100 Wh
    //   ac = ceil(1,100 * 110 / 100) = 1,210 Wh
    // Loaded trip (12,300 m):
    //   base = 12,300 * 160 / 1000 = 1,968 Wh
    //   traffic = ceil(1,968 * 125 / 100) = 2,460 Wh
    //   ac = ceil(2,460 * 110 / 100) = 2,706 Wh
    // Golden total = 1,210 + 2,706 = 3,916 Wh
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

    // Touching intervals [100, 200) and [200, 300) on 1 plug: valid
    ChargingSession s1 =
        new ChargingSession(
            "S-1",
            cab1.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(100),
            t0.plusSeconds(200),
            20_000L,
            30_000L,
            500_000L);
    ChargingSession s2 =
        new ChargingSession(
            "S-2",
            cab2.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(200),
            t0.plusSeconds(300),
            20_000L,
            30_000L,
            500_000L);

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

    // Overlapping intervals [100, 200) and [199, 300) on 1 plug: invalid (H2_CHARGER_CAPACITY)
    ChargingSession s2Overlap =
        new ChargingSession(
            "S-2-OV",
            cab2.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(199),
            t0.plusSeconds(300),
            20_000L,
            30_000L,
            500_000L);
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

  @Test
  @DisplayName(
      "Golden physics 5: Tariff costs across multi-windows (10.5M) and DST fall-back (27M)")
  void tariffCostAcrossWindowAndDstGoldenTest() {
    HubId hubId = HubId.of("HUB-LONDON");
    ZoneId london = ZoneId.of("Europe/London");

    // 1. Two-hour session crossing off-peak (100,000 micros/kWh) to peak (250,000 micros/kWh)
    // 05:00-06:00 (30,000 Wh @ 100,000 micros/kWh) = 3,000,000 micros
    // 06:00-07:00 (30,000 Wh @ 250,000 micros/kWh) = 7,500,000 micros
    // Total = 10,500,000 micros ($10.50)
    Instant sStart = Instant.parse("2026-06-01T05:00:00Z");
    Instant sEnd = Instant.parse("2026-06-01T07:00:00Z");
    List<UtcTariffWindow> windows =
        List.of(
            new UtcTariffWindow(hubId, sStart, sStart.plusSeconds(3600), 100_000L),
            new UtcTariffWindow(hubId, sStart.plusSeconds(3600), sEnd, 250_000L));

    long costTwoHour = TariffCostValidator.calculateCostMicros(sStart, sEnd, 60_000L, windows);
    assertThat(costTwoHour).isEqualTo(10_500_000L);

    // 2. DST fall-back day (2026-10-25) in Europe/London:
    // Night tariff 22:00 to 07:00 local spans 9 elapsed hours due to 1-hour fall back.
    // 9 hours @ 30 kW = 270,000 Wh @ 100,000 micro-units/kWh = 27,000,000 micros ($27.00).
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
  @DisplayName(
      "Differential test: domain ChargingCurve duration matches validator expectation across random profiles")
  void differentialChargingPhysicsDomainVsValidator() {
    Random rng = new Random(42);
    ChargingCurve domainCurve = ChargingCurve.defaultCurve();

    for (int iter = 0; iter < 100; iter++) {
      long capacityWh = 40_000L + rng.nextInt(60_000); // 40 kWh to 100 kWh
      int powerKw = 20 + rng.nextInt(150); // 20 kW to 170 kW
      long startSocWh = rng.nextLong(capacityWh - 5_000);
      long remaining = capacityWh - startSocWh;
      long targetSocWh = startSocWh + 1 + rng.nextLong(remaining);

      long domainDuration =
          domainCurve.calculateDurationSeconds(startSocWh, targetSocWh, capacityWh, powerKw);

      // Independently compute using identical piecewise linear physics:
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
}
