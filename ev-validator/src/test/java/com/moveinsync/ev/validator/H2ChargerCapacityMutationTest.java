package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.ChargerStatus;
import com.moveinsync.ev.domain.hub.OutageWindow;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Single-fault mutation tests for invariant H2 (charger capacity and availability). */
class H2ChargerCapacityMutationTest extends ValidatorMutationTestFixture {

  @Test
  @DisplayName("Mutation 3: Overlapping two charging sessions by 1 second is caught (H2)")
  void mutationOverlapSessionsBy1Second() {
    Instant tStart = Instant.parse("2026-10-30T08:57:59Z");
    Instant tEnd = tStart.plusSeconds(1440);

    ChargingSession overlappingSession =
        new ChargingSession(
            "SESS-OVERLAP-1S",
            cab2.id(),
            hub1.id(),
            charger1.id(),
            tStart,
            tEnd,
            20_000L,
            40_000L,
            500_000L);

    VehicleSchedule schedule2 =
        new VehicleSchedule(cab2, hub1Loc, 20_000L, tStart, List.of(overlappingSession));

    List<VehicleSchedule> schedules = new ArrayList<>(baselineValidPlan.vehicleSchedules());
    schedules.add(schedule2);

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-3",
            "tenant-1",
            baselineValidPlan.horizonStart(),
            tEnd.plusSeconds(3600),
            schedules,
            List.of(),
            999L,
            "hash-mut-3");

    ValidationInput input =
        ValidationInput.builder()
            .tenantId(TenantId.of("tenant-1"))
            .horizon(baselineValidPlan.horizonStart(), tEnd.plusSeconds(3600))
            .consumptionModel(context.consumptionModel())
            .distanceProvider(context.distanceProvider())
            .chargingCurve(context.chargingCurve())
            .batteryFloorPercentage(context.batteryFloorPercentage())
            .enforceReserveToHub(context.enforceReserveToHub())
            .addHub(hub1)
            .addCharger(charger1)
            .addVehicle(cab1, hub1Loc, 50_000L)
            .addVehicle(cab2, hub1Loc, 20_000L)
            .addTrip(baselineTrip)
            .build();

    ValidationReport report = validator.validate(input, mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H2_CHARGER_CAPACITY)).isNotEmpty();
  }

  @Test
  @DisplayName(
      "Mutation 16: Session scheduled on FAULTY charger produces CHARGER_UNAVAILABLE (F-06)")
  void mutationSessionOnFaultyCharger() {
    Charger faultyCharger =
        new Charger(
            TenantId.of("tenant-1"),
            ChargerId.of("CHG-FAULTY"),
            hub1.id(),
            50,
            ChargerStatus.FAULTY,
            List.of());
    ValidationInput input =
        ValidationInput.builder()
            .tenantId(TenantId.of("tenant-1"))
            .horizon(baselineValidPlan.horizonStart(), baselineValidPlan.horizonEnd())
            .consumptionModel(context.consumptionModel())
            .distanceProvider(context.distanceProvider())
            .chargingCurve(context.chargingCurve())
            .batteryFloorPercentage(context.batteryFloorPercentage())
            .enforceReserveToHub(context.enforceReserveToHub())
            .addHub(hub1)
            .addCharger(faultyCharger)
            .addVehicle(cab1, hub1Loc, 50_000L)
            .addTrip(baselineTrip)
            .build();

    ChargingSession sessionOnFaulty =
        new ChargingSession(
            "SESS-FAULTY",
            cab1.id(),
            hub1.id(),
            faultyCharger.id(),
            baselineTrip.startTs().plusSeconds(1800),
            baselineTrip.startTs().plusSeconds(2880),
            47_000L,
            55_000L,
            750_000L);
    VehicleSchedule sched =
        new VehicleSchedule(
            cab1, hub1Loc, 50_000L, baselineTrip.startTs(), List.of(sessionOnFaulty));
    FleetPlan mutatedPlan =
        new FleetPlan(
            "P-FAULTY",
            "tenant-1",
            baselineValidPlan.horizonStart(),
            baselineValidPlan.horizonEnd(),
            List.of(sched),
            List.of(),
            999L,
            "hash");

    ValidationReport report = validator.validate(input, mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CHARGER_UNAVAILABLE)).isNotEmpty();
  }

  @Test
  @DisplayName(
      "Mutation 17: Session scheduled on MAINTENANCE charger produces CHARGER_UNAVAILABLE (F-06)")
  void mutationSessionOnMaintenanceCharger() {
    Charger maintCharger =
        new Charger(
            TenantId.of("tenant-1"),
            ChargerId.of("CHG-MAINT"),
            hub1.id(),
            50,
            ChargerStatus.MAINTENANCE,
            List.of());
    ValidationInput input =
        ValidationInput.builder()
            .tenantId(TenantId.of("tenant-1"))
            .horizon(baselineValidPlan.horizonStart(), baselineValidPlan.horizonEnd())
            .consumptionModel(context.consumptionModel())
            .distanceProvider(context.distanceProvider())
            .chargingCurve(context.chargingCurve())
            .batteryFloorPercentage(context.batteryFloorPercentage())
            .enforceReserveToHub(context.enforceReserveToHub())
            .addHub(hub1)
            .addCharger(maintCharger)
            .addVehicle(cab1, hub1Loc, 50_000L)
            .addTrip(baselineTrip)
            .build();

    ChargingSession sessionOnMaint =
        new ChargingSession(
            "SESS-MAINT",
            cab1.id(),
            hub1.id(),
            maintCharger.id(),
            baselineTrip.startTs().plusSeconds(1800),
            baselineTrip.startTs().plusSeconds(2880),
            47_000L,
            55_000L,
            750_000L);
    VehicleSchedule sched =
        new VehicleSchedule(
            cab1, hub1Loc, 50_000L, baselineTrip.startTs(), List.of(sessionOnMaint));
    FleetPlan mutatedPlan =
        new FleetPlan(
            "P-MAINT",
            "tenant-1",
            baselineValidPlan.horizonStart(),
            baselineValidPlan.horizonEnd(),
            List.of(sched),
            List.of(),
            999L,
            "hash");

    ValidationReport report = validator.validate(input, mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CHARGER_UNAVAILABLE)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 18: Session during charger outage window produces CHARGER_OUTAGE (F-06)")
  void mutationSessionDuringChargerOutageWindow() {
    Instant tStart = baselineTrip.startTs().plusSeconds(1800);
    Instant tEnd = baselineTrip.startTs().plusSeconds(2880);
    OutageWindow outage =
        new OutageWindow(tStart.minusSeconds(300), tEnd.plusSeconds(300), "Grid curtailment");
    Charger outageCharger =
        new Charger(
            TenantId.of("tenant-1"),
            ChargerId.of("CHG-OUTAGE"),
            hub1.id(),
            50,
            ChargerStatus.OPERATIONAL,
            List.of(outage));
    ValidationInput input =
        ValidationInput.builder()
            .tenantId(TenantId.of("tenant-1"))
            .horizon(baselineValidPlan.horizonStart(), baselineValidPlan.horizonEnd())
            .consumptionModel(context.consumptionModel())
            .distanceProvider(context.distanceProvider())
            .chargingCurve(context.chargingCurve())
            .batteryFloorPercentage(context.batteryFloorPercentage())
            .enforceReserveToHub(context.enforceReserveToHub())
            .addHub(hub1)
            .addCharger(outageCharger)
            .addVehicle(cab1, hub1Loc, 50_000L)
            .addTrip(baselineTrip)
            .build();

    ChargingSession sessionDuringOutage =
        new ChargingSession(
            "SESS-OUTAGE",
            cab1.id(),
            hub1.id(),
            outageCharger.id(),
            tStart,
            tEnd,
            47_000L,
            55_000L,
            750_000L);
    VehicleSchedule sched =
        new VehicleSchedule(
            cab1, hub1Loc, 50_000L, baselineTrip.startTs(), List.of(sessionDuringOutage));
    FleetPlan mutatedPlan =
        new FleetPlan(
            "P-OUTAGE",
            "tenant-1",
            baselineValidPlan.horizonStart(),
            baselineValidPlan.horizonEnd(),
            List.of(sched),
            List.of(),
            999L,
            "hash");

    ValidationReport report = validator.validate(input, mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CHARGER_UNAVAILABLE)).isNotEmpty();
  }
}
