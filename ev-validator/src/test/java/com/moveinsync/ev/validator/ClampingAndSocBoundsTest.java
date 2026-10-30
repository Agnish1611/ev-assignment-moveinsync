package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.charging.ChargingCurve;
import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dedicated test suite verifying that out-of-range SoC values are strictly flagged with
 * RuleId.SOC_OUT_OF_RANGE or RuleId.CLAIMED_SOC_MISMATCH and never silently clamped or coerced.
 */
class ClampingAndSocBoundsTest {

  private final Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
  private final TenantId tenant = TenantId.of("tenant-1");
  private final Location hubLoc = Location.of("LOC-HUB", "Hub Loc");
  private final Location pickLoc = Location.of("LOC-PICK", "Pick Loc");
  private final Location dropLoc = Location.of("LOC-DROP", "Drop Loc");

  private final Hub hub = Hub.of("HUB-1", "Main Hub", hubLoc, ZoneId.of("UTC"), 1);
  private final Charger charger = Charger.of("CHG-1", "HUB-1", 50);
  private final Vehicle cab = new Vehicle(VehicleId.of("CAB-01"), 60_000L, 150L, 50);
  private final Trip trip =
      new Trip(
          TripId.of("TRIP-01"),
          t0.plusSeconds(1800),
          pickLoc,
          dropLoc,
          10_000,
          1200,
          t0.plusSeconds(5400),
          "E-1");

  private final DistanceProvider distanceProvider = new ConstantSpeedDistanceProvider(5000, 10);
  private final ConstantConsumptionModel consumptionModel = new ConstantConsumptionModel();
  private final ChargingCurve chargingCurve = ChargingCurve.defaultCurve();

  private ValidationInput input;
  private PlanValidator validator;

  @BeforeEach
  void setUp() {
    validator = new PlanValidator();
    input =
        ValidationInput.builder()
            .tenantId(tenant)
            .horizon(t0, t0.plusSeconds(14400))
            .addHub(hub)
            .addCharger(charger)
            .addVehicle(cab, hubLoc, 50_000L)
            .addTrip(trip)
            .addUtcTariff(new UtcTariffWindow(hub.id(), t0, t0.plusSeconds(14400), 100_000L))
            .distanceProvider(distanceProvider)
            .consumptionModel(consumptionModel)
            .chargingCurve(chargingCurve)
            .batteryFloorPercentage(15)
            .enforceReserveToHub(true)
            .build();
  }

  @Test
  @DisplayName("Charging session with target SoC exceeding capacity is invalid (SOC_OUT_OF_RANGE)")
  void targetSocExceedingCapacityProducesSocOutOfRange() {
    // 60,001 Wh > 60,000 Wh capacity
    ChargingSession session =
        new ChargingSession(
            "S-OVERCAP",
            cab.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(1800),
            t0.plusSeconds(3600),
            50_000L,
            60_001L,
            100_000L);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of(session));
    FleetPlan plan =
        new FleetPlan(
            "P-OVERCAP",
            tenant.value(),
            t0,
            t0.plusSeconds(14400),
            List.of(sched),
            List.of(),
            1L,
            "h");

    ValidationReport report = validator.validate(input, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.SOC_OUT_OF_RANGE)).isNotEmpty();
  }

  @Test
  @DisplayName("Charging session with negative target SoC is invalid (SOC_OUT_OF_RANGE)")
  void negativeTargetSocProducesSocOutOfRange() {
    ChargingSession session =
        new ChargingSession(
            "S-NEGTARGET",
            cab.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(1800),
            t0.plusSeconds(3600),
            50_000L,
            -500L,
            100_000L);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of(session));
    FleetPlan plan =
        new FleetPlan(
            "P-NEGTARGET",
            tenant.value(),
            t0,
            t0.plusSeconds(14400),
            List.of(sched),
            List.of(),
            1L,
            "h");

    ValidationReport report = validator.validate(input, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.SOC_OUT_OF_RANGE)).isNotEmpty();
  }

  @Test
  @DisplayName("Charging session with negative start SoC is invalid (SOC_OUT_OF_RANGE)")
  void negativeStartSocProducesSocOutOfRange() {
    ChargingSession session =
        new ChargingSession(
            "S-NEGSTART",
            cab.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(1800),
            t0.plusSeconds(3600),
            -1L,
            50_000L,
            100_000L);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of(session));
    FleetPlan plan =
        new FleetPlan(
            "P-NEGSTART",
            tenant.value(),
            t0,
            t0.plusSeconds(14400),
            List.of(sched),
            List.of(),
            1L,
            "h");

    ValidationReport report = validator.validate(input, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.SOC_OUT_OF_RANGE)).isNotEmpty();
  }

  @Test
  @DisplayName("Charging session with start SoC exceeding capacity is invalid (SOC_OUT_OF_RANGE)")
  void startSocExceedingCapacityProducesSocOutOfRange() {
    ChargingSession session =
        new ChargingSession(
            "S-OVERSTART",
            cab.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(1800),
            t0.plusSeconds(3600),
            60_001L,
            60_000L,
            100_000L);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of(session));
    FleetPlan plan =
        new FleetPlan(
            "P-OVERSTART",
            tenant.value(),
            t0,
            t0.plusSeconds(14400),
            List.of(sched),
            List.of(),
            1L,
            "h");

    ValidationReport report = validator.validate(input, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.SOC_OUT_OF_RANGE)).isNotEmpty();
  }

  @Test
  @DisplayName("Charging session target SoC exceeding curve delivery produces CLAIMED_SOC_MISMATCH")
  void targetSocExceedingCurveDeliveryProducesClaimedSocMismatch() {
    // 600 seconds from 50,000 Wh at 50 kW delivers ~8,333 Wh (up to ~58,333 Wh).
    // Session claims target = 60,000 Wh!
    ChargingSession session =
        new ChargingSession(
            "S-EXCEEDCURVE",
            cab.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(1800),
            t0.plusSeconds(2400),
            50_000L,
            60_000L,
            100_000L);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of(session));
    FleetPlan plan =
        new FleetPlan(
            "P-EXCEEDCURVE",
            tenant.value(),
            t0,
            t0.plusSeconds(14400),
            List.of(sched),
            List.of(),
            1L,
            "h");

    ValidationReport report = validator.validate(input, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CLAIMED_SOC_MISMATCH)).isNotEmpty();
  }

  @Test
  @DisplayName("Session start SoC greater than target SoC produces H6_CHARGING_CURVE")
  void sessionStartSocGreaterThanTargetSocProducesH6() {
    ChargingSession session =
        new ChargingSession(
            "S-START-GT-TARGET",
            cab.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(1800),
            t0.plusSeconds(3600),
            50_000L,
            40_000L,
            100_000L);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of(session));
    FleetPlan plan =
        new FleetPlan(
            "P-START-GT-TARGET",
            tenant.value(),
            t0,
            t0.plusSeconds(14400),
            List.of(sched),
            List.of(),
            1L,
            "h");

    ValidationReport report = validator.validate(input, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H6_CHARGING_CURVE)).isNotEmpty();
  }

  @Test
  @DisplayName("Initial SoC exceeding battery capacity produces SOC_OUT_OF_RANGE")
  void initialSocExceedingCapacityProducesSocOutOfRange() {
    // 65,000 Wh > 60,000 Wh capacity
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 65_000L, t0, List.of());
    FleetPlan plan =
        new FleetPlan(
            "P-OVERINIT",
            tenant.value(),
            t0,
            t0.plusSeconds(14400),
            List.of(sched),
            List.of(),
            1L,
            "h");

    ValidationReport report = validator.validate(input, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.SOC_OUT_OF_RANGE)).isNotEmpty();
  }

  @Test
  @DisplayName("Negative initial SoC produces SOC_OUT_OF_RANGE")
  void negativeInitialSocProducesSocOutOfRange() {
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, -100L, t0, List.of());
    FleetPlan plan =
        new FleetPlan(
            "P-NEGINIT",
            tenant.value(),
            t0,
            t0.plusSeconds(14400),
            List.of(sched),
            List.of(),
            1L,
            "h");

    ValidationReport report = validator.validate(input, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.SOC_OUT_OF_RANGE)).isNotEmpty();
  }

  @Test
  @DisplayName("Claimed departure SoC after dropoff exceeding capacity produces SOC_OUT_OF_RANGE")
  void departureAfterDropoffSocExceedingCapacityProducesSocOutOfRange() {
    // Dropoff SoC is claimed as 65,000 Wh (> 60,000 Wh capacity)
    TripAssignment assign =
        new TripAssignment(
            trip,
            cab.id(),
            trip.startTs(),
            trip.startTs().plusSeconds(1200),
            5000,
            600,
            750L,
            49_250L,
            65_000L);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of(assign));
    FleetPlan plan =
        new FleetPlan(
            "P-OVERDROPOFF",
            tenant.value(),
            t0,
            t0.plusSeconds(14400),
            List.of(sched),
            List.of(),
            1L,
            "h");

    ValidationReport report = validator.validate(input, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.SOC_OUT_OF_RANGE)).isNotEmpty();
  }

  @Test
  @DisplayName("Claimed negative departure SoC after dropoff produces SOC_OUT_OF_RANGE")
  void negativeDepartureAfterDropoffSocProducesSocOutOfRange() {
    TripAssignment assign =
        new TripAssignment(
            trip,
            cab.id(),
            trip.startTs(),
            trip.startTs().plusSeconds(1200),
            5000,
            600,
            750L,
            49_250L,
            -500L);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of(assign));
    FleetPlan plan =
        new FleetPlan(
            "P-NEGDROPOFF",
            tenant.value(),
            t0,
            t0.plusSeconds(14400),
            List.of(sched),
            List.of(),
            1L,
            "h");

    ValidationReport report = validator.validate(input, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.SOC_OUT_OF_RANGE)).isNotEmpty();
  }
}
