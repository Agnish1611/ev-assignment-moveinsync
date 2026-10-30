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
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bidirectional claim mismatch tests (+1 Wh and -1 Wh) for session start SoC, session end SoC, and
 * session cost (+/-1 micro-unit), plus F-03 regression.
 */
class SocSessionClaimMismatchValidatorTest {

  private final Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
  private final TenantId tenant = TenantId.of("tenant-1");
  private final Location hubLoc = Location.of("LOC-HUB", "Hub Loc");

  private final Hub hub = Hub.of("tenant-1", "HUB-1", "Main Hub", hubLoc, ZoneId.of("UTC"), 1);
  private final Charger charger = Charger.of("tenant-1", "CHG-1", "HUB-1", 50);
  private final Vehicle cab =
      new Vehicle(TenantId.of("tenant-1"), VehicleId.of("CAB-01"), 60_000L, 150L, 50);

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
            .addUtcTariff(new UtcTariffWindow(hub.id(), t0, t0.plusSeconds(14400), 100_000L))
            .distanceProvider(distanceProvider)
            .consumptionModel(consumptionModel)
            .chargingCurve(chargingCurve)
            .batteryFloorPercentage(15)
            .enforceReserveToHub(true)
            .build();
  }

  @Test
  @DisplayName("Original F-03: Plug SoC inflation (10,000 arrival / 50,000 declared start)")
  void plugSocInflationSessionStartAboveArrivalProducesClaimedSocMismatch() {
    ChargingSession inflatedSession =
        new ChargingSession(
            "S-INFLATED",
            cab.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(1800),
            t0.plusSeconds(3600),
            50_000L,
            55_000L,
            500_000L);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 10_000L, t0, List.of(inflatedSession));
    FleetPlan plan =
        new FleetPlan(
            "P-INFLATED",
            tenant.value(),
            t0,
            t0.plusSeconds(14400),
            List.of(sched),
            List.of(),
            1L,
            "h");

    ValidationInput input10k =
        ValidationInput.builder()
            .tenantId(tenant)
            .horizon(t0, t0.plusSeconds(86400))
            .addHub(hub)
            .addCharger(charger)
            .addVehicle(cab, hubLoc, 10_000L)
            .distanceProvider(distanceProvider)
            .consumptionModel(consumptionModel)
            .batteryFloorPercentage(15)
            .enforceReserveToHub(false)
            .build();

    ValidationReport report = validator.validate(input10k, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CLAIMED_SOC_MISMATCH)).isNotEmpty();
  }

  @Test
  @DisplayName("Session start SoC +1 Wh produces CLAIMED_SOC_MISMATCH")
  void sessionStartSocPlus1WhProducesClaimedSocMismatch() {
    ChargingSession session =
        new ChargingSession(
            "S-START-PLUS",
            cab.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(1800),
            t0.plusSeconds(2880),
            50_001L,
            55_000L,
            500_000L);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of(session));
    FleetPlan plan =
        new FleetPlan(
            "P-START-PLUS",
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
  @DisplayName("Session start SoC -1 Wh produces CLAIMED_SOC_MISMATCH")
  void sessionStartSocMinus1WhProducesClaimedSocMismatch() {
    ChargingSession session =
        new ChargingSession(
            "S-START-MINUS",
            cab.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(1800),
            t0.plusSeconds(2880),
            49_999L,
            55_000L,
            500_000L);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of(session));
    FleetPlan plan =
        new FleetPlan(
            "P-START-MINUS",
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
  @DisplayName("Session end SoC +1 Wh produces CLAIMED_SOC_MISMATCH")
  void sessionEndSocPlus1WhProducesClaimedSocMismatch() {
    ChargingSession session =
        new ChargingSession(
            "S-END-PLUS",
            cab.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(1800),
            t0.plusSeconds(2400),
            50_000L,
            54_167L,
            500_000L);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of(session));
    FleetPlan plan =
        new FleetPlan(
            "P-END-PLUS",
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
  @DisplayName("Session end SoC -1 Wh produces CLAIMED_SOC_MISMATCH")
  void sessionEndSocMinus1WhProducesClaimedSocMismatch() {
    ChargingSession session =
        new ChargingSession(
            "S-END-MINUS",
            cab.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(1800),
            t0.plusSeconds(2400),
            50_000L,
            54_165L,
            500_000L);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of(session));
    FleetPlan plan =
        new FleetPlan(
            "P-END-MINUS",
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
  @DisplayName("Session cost +1 micro-unit produces COST_MISMATCH")
  void sessionCostPlus1MicrounitProducesCostMismatch() {
    ChargingSession session =
        new ChargingSession(
            "S-COST-PLUS",
            cab.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(1800),
            t0.plusSeconds(2880),
            50_000L,
            55_000L,
            500_001L);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of(session));
    FleetPlan plan =
        new FleetPlan(
            "P-COST-PLUS",
            tenant.value(),
            t0,
            t0.plusSeconds(14400),
            List.of(sched),
            List.of(),
            1L,
            "h");

    ValidationReport report = validator.validate(input, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.COST_MISMATCH)).isNotEmpty();
  }

  @Test
  @DisplayName("Session cost -1 micro-unit produces COST_MISMATCH")
  void sessionCostMinus1MicrounitProducesCostMismatch() {
    ChargingSession session =
        new ChargingSession(
            "S-COST-MINUS",
            cab.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(1800),
            t0.plusSeconds(2880),
            50_000L,
            55_000L,
            499_999L);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of(session));
    FleetPlan plan =
        new FleetPlan(
            "P-COST-MINUS",
            tenant.value(),
            t0,
            t0.plusSeconds(14400),
            List.of(sched),
            List.of(),
            1L,
            "h");

    ValidationReport report = validator.validate(input, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.COST_MISMATCH)).isNotEmpty();
  }
}
