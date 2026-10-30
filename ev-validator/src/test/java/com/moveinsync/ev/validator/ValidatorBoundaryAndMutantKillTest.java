package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
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
 * Targeted tests killing boundary and conditional mutation survivors identified during PIT
 * analysis.
 */
class ValidatorBoundaryAndMutantKillTest {

  private final TenantId tenant = TenantId.of("tenant-mut-kill");
  private final Instant t0 = Instant.parse("2026-10-30T00:00:00Z");
  private final Location depot = Location.of("LOC-DEPOT", "Depot");

  @Test
  @DisplayName("Kill BatteryFloorValidator mutant: initialSoc == floorWh is strictly VALID")
  void initialSocExactlyAtFloorIsStrictlyValid() {
    // 50,000 Wh battery, 15% floor = 7,500 Wh.
    Vehicle cab = new Vehicle(tenant, VehicleId.of("CAB-FLOOR"), 50_000L, 150L, 50);
    Hub hub = Hub.of(tenant.value(), "HUB-1", "H1", depot, ZoneId.of("UTC"), 1);
    Charger charger = Charger.of(tenant.value(), "CHG-1", "HUB-1", 50);

    ValidationInput input =
        ValidationInput.builder()
            .tenantId(tenant)
            .horizon(t0, t0.plusSeconds(3600))
            .addHub(hub)
            .addCharger(charger)
            .addVehicle(cab, depot, 7_500L) // Exactly on floor
            .distanceProvider(new ConstantSpeedDistanceProvider(5000, 10))
            .consumptionModel(new ConstantConsumptionModel())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(false)
            .build();

    // Idle plan with initialSoc exactly at floor
    FleetPlan plan =
        new FleetPlan(
            "P-EXACT-FLOOR",
            tenant.value(),
            t0,
            t0.plusSeconds(3600),
            List.of(new VehicleSchedule(cab, depot, 7_500L, t0, List.of())),
            List.of(),
            1L,
            "h");

    ValidationReport report = new PlanValidator().validate(input, plan);
    assertThat(report.isValid()).isTrue();
    assertThat(report.violationsFor(RuleId.H1_BATTERY_FLOOR)).isEmpty();
    assertThat(report.violationsFor(RuleId.SOC_OUT_OF_RANGE)).isEmpty();
  }

  @Test
  @DisplayName(
      "Kill BatteryFloorValidator mutant: initialSoc == 0 Wh and capacity Wh are within range")
  void initialSocAtZeroAndCapacityAreWithinSocBounds() {
    Vehicle cab = new Vehicle(tenant, VehicleId.of("CAB-BOUNDS"), 50_000L, 150L, 50);
    Hub hub = Hub.of(tenant.value(), "HUB-1", "H1", depot, ZoneId.of("UTC"), 1);
    Charger charger = Charger.of(tenant.value(), "CHG-1", "HUB-1", 50);

    ValidationInput input =
        ValidationInput.builder()
            .tenantId(tenant)
            .horizon(t0, t0.plusSeconds(3600))
            .addHub(hub)
            .addCharger(charger)
            .addVehicle(cab, depot, 0L)
            .distanceProvider(new ConstantSpeedDistanceProvider(5000, 10))
            .consumptionModel(new ConstantConsumptionModel())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(false)
            .build();

    // 0 Wh is below 15% floor but within [0, capacity] SOC_OUT_OF_RANGE bounds
    FleetPlan zeroPlan =
        new FleetPlan(
            "P-ZERO",
            tenant.value(),
            t0,
            t0.plusSeconds(3600),
            List.of(new VehicleSchedule(cab, depot, 0L, t0, List.of())),
            List.of(),
            1L,
            "h");

    ValidationReport reportZero = new PlanValidator().validate(input, zeroPlan);
    assertThat(reportZero.violationsFor(RuleId.SOC_OUT_OF_RANGE)).isEmpty();
    assertThat(reportZero.violationsFor(RuleId.H1_BATTERY_FLOOR)).isNotEmpty();

    // Capacity Wh is within [0, capacity] SOC_OUT_OF_RANGE bounds and above floor
    FleetPlan capPlan =
        new FleetPlan(
            "P-CAP",
            tenant.value(),
            t0,
            t0.plusSeconds(3600),
            List.of(new VehicleSchedule(cab, depot, 50_000L, t0, List.of())),
            List.of(),
            1L,
            "h");

    ValidationReport reportCap = new PlanValidator().validate(input, capPlan);
    assertThat(reportCap.violationsFor(RuleId.SOC_OUT_OF_RANGE)).isEmpty();
    assertThat(reportCap.violationsFor(RuleId.H1_BATTERY_FLOOR)).isEmpty();
  }

  @Test
  @DisplayName("Kill ValidationInputBuilder boundary mutants: floor 1 and 99, idlePowerW 0")
  void validationInputBuilderExactBoundaries() {
    Vehicle cab = new Vehicle(tenant, VehicleId.of("CAB-01"), 50_000L, 150L, 50);
    Hub hub = Hub.of(tenant.value(), "HUB-1", "H1", depot, ZoneId.of("UTC"), 1);
    Charger chg = Charger.of(tenant.value(), "CHG-1", "HUB-1", 50);

    ValidationInputBuilder base =
        ValidationInput.builder()
            .tenantId(tenant)
            .horizon(t0, t0.plusSeconds(3600))
            .addHub(hub)
            .addCharger(chg)
            .distanceProvider(new ConstantSpeedDistanceProvider(5000, 10))
            .consumptionModel(new ConstantConsumptionModel());

    // Exact floor boundaries [1, 99]
    assertThatCode(() -> base.batteryFloorPercentage(1).addVehicle(cab, depot, 10_000L).build())
        .doesNotThrowAnyException();
    assertThatCode(() -> base.batteryFloorPercentage(99).build()).doesNotThrowAnyException();
    assertThatThrownBy(() -> base.batteryFloorPercentage(0).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> base.batteryFloorPercentage(100).build())
        .isInstanceOf(IllegalArgumentException.class);

    // Exact idlePower boundary: 0 is valid, -1 is invalid
    assertThatCode(() -> base.idlePowerW(0).build()).doesNotThrowAnyException();
    assertThatThrownBy(() -> base.idlePowerW(-1)).isInstanceOf(IllegalArgumentException.class);

    // Exact initialSoc boundaries in addVehicle: 0 and capacity
    assertThatCode(() -> base.addVehicle(cab, depot, 0L).build()).doesNotThrowAnyException();
    assertThatCode(() -> base.addVehicle(cab, depot, 50_000L).build()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName(
      "Kill ValidationReport boundary mutants: total == maxCap not truncated, totalDetailedSummary format")
  void validationReportExactCapAndSummaryFormatting() {
    PlanViolation v1 =
        PlanViolation.ofVehicle(RuleId.H1_BATTERY_FLOOR, VehicleId.of("CAB-1"), t0, "msg1");
    PlanViolation v2 =
        PlanViolation.ofVehicle(RuleId.H3_TRIP_TIMING, VehicleId.of("CAB-1"), t0, "msg2");

    // Exactly 2 violations capped at 2: NOT truncated
    ValidationReport repExact = ValidationReport.createCapped(List.of(v1, v2), 2, 1, 1, 0);
    assertThat(repExact.isTruncated()).isFalse();
    assertThat(repExact.violations()).hasSize(2);
    assertThat(repExact.totalViolationsCount()).isEqualTo(2);

    // Exactly 2 violations capped at 1: IS truncated
    ValidationReport repCapped = ValidationReport.createCapped(List.of(v1, v2), 1, 1, 1, 0);
    assertThat(repCapped.isTruncated()).isTrue();
    assertThat(repCapped.violations()).hasSize(1);
    assertThat(repCapped.totalViolationsCount()).isEqualTo(2);

    // Test toDetailedSummary execution on failed report (kills toDetailedSummary mutant)
    String summary = repCapped.toDetailedSummary();
    assertThat(summary).contains("FAILED with 2 violation(s)");
    assertThat(summary).contains("[Report truncated to 1]");
    assertThat(summary).contains("[H1_BATTERY_FLOOR] msg1");
  }

  @Test
  @DisplayName(
      "Kill AssignmentValidator sorting mutant: activities added out-of-order are sorted cleanly")
  void activitiesAddedOutOfOrderAreSortedAndValidatedCleanly() {
    Vehicle cab = new Vehicle(tenant, VehicleId.of("CAB-SORT"), 60_000L, 150L, 50);
    Hub hub = Hub.of(tenant.value(), "HUB-1", "H1", depot, ZoneId.of("UTC"), 2);
    Charger chg1 = Charger.of(tenant.value(), "CHG-1", "HUB-1", 50);
    Charger chg2 = Charger.of(tenant.value(), "CHG-2", "HUB-1", 50);

    ValidationInput input =
        ValidationInput.builder()
            .tenantId(tenant)
            .horizon(t0, t0.plusSeconds(7200))
            .addHub(hub)
            .addCharger(chg1)
            .addCharger(chg2)
            .addVehicle(cab, depot, 40_000L)
            .distanceProvider(new ConstantSpeedDistanceProvider(5000, 10))
            .consumptionModel(new ConstantConsumptionModel())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(false)
            .build();

    ChargingSession sEarly =
        new ChargingSession(
            "S-EARLY",
            cab.id(),
            hub.id(),
            chg1.id(),
            t0.plusSeconds(100),
            t0.plusSeconds(600),
            40_000L,
            46_000L,
            600_000L);
    ChargingSession sLate =
        new ChargingSession(
            "S-LATE",
            cab.id(),
            hub.id(),
            chg2.id(),
            t0.plusSeconds(800),
            t0.plusSeconds(1300),
            46_000L,
            52_000L,
            600_000L);

    // Pass activities in reverse chronological order: sLate then sEarly
    VehicleSchedule reverseSched =
        new VehicleSchedule(cab, depot, 40_000L, t0, List.of(sLate, sEarly));
    FleetPlan plan =
        new FleetPlan(
            "P-REVERSE",
            tenant.value(),
            t0,
            t0.plusSeconds(7200),
            List.of(reverseSched),
            List.of(),
            1L,
            "h");

    ValidationReport report = new PlanValidator().validate(input, plan);
    // If activities were not sorted, sLate [800, 1300] compared with sEarly [100, 600] would be
    // flagged as an overlap!
    assertThat(report.violationsFor(RuleId.H4_ASSIGNMENT_OVERLAP)).isEmpty();
  }

  @Test
  @DisplayName(
      "Kill ValidationInputBuilder tariff sorting mutant: unordered tariff windows sorted without false overlap")
  void unorderedTariffWindowsSortedCleanly() {
    HubId hubId = HubId.of("HUB-1");
    UtcTariffWindow early = new UtcTariffWindow(hubId, t0, t0.plusSeconds(1800), 100_000L);
    UtcTariffWindow late =
        new UtcTariffWindow(hubId, t0.plusSeconds(1800), t0.plusSeconds(3600), 120_000L);

    // Pass windows in reverse order: late then early
    assertThatCode(
            () ->
                ValidationInput.builder()
                    .tenantId(tenant)
                    .horizon(t0, t0.plusSeconds(3600))
                    .addHub(Hub.of(tenant.value(), "HUB-1", "H1", depot, ZoneId.of("UTC"), 1))
                    .addCharger(Charger.of(tenant.value(), "CHG-1", "HUB-1", 50))
                    .addUtcTariff(late)
                    .addUtcTariff(early)
                    .distanceProvider(new ConstantSpeedDistanceProvider(5000, 10))
                    .consumptionModel(new ConstantConsumptionModel())
                    .build())
        .doesNotThrowAnyException();
  }
}
