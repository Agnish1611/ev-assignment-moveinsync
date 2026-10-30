package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Single-fault mutation tests for cost calculations and cross-tenant isolation. */
class CostAndTenancyMutationTest extends ValidatorMutationTestFixture {

  @Test
  @DisplayName("Mutation 21: Cross-tenant trip produces TENANT_MISMATCH (F-12)")
  void mutationCrossTenantTripProducesTenantMismatch() {
    Trip crossTenantTrip =
        new Trip(
            TenantId.of("tenant-malicious"),
            TripId.of("T-CROSS"),
            baselineTrip.startTs(),
            pickupLoc,
            dropoffLoc,
            10_000,
            1200,
            baselineTrip.latestFinishTs(),
            "EMP-CROSS");
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
            .addCharger(charger1)
            .addVehicle(cab1, hub1Loc, 50_000L)
            .addTrip(crossTenantTrip)
            .build();

    TripAssignment assign =
        new TripAssignment(
            crossTenantTrip,
            cab1.id(),
            crossTenantTrip.startTs(),
            crossTenantTrip.plannedDropoffTs(),
            5000,
            600,
            750L,
            49_250L,
            47_750L);
    VehicleSchedule sched =
        new VehicleSchedule(
            cab1, hub1Loc, 50_000L, baselineValidPlan.horizonStart(), List.of(assign));
    FleetPlan mutatedPlan =
        new FleetPlan(
            "P-CROSS",
            "tenant-1",
            baselineValidPlan.horizonStart(),
            baselineValidPlan.horizonEnd(),
            List.of(sched),
            List.of(),
            999L,
            "hash");

    ValidationReport report = validator.validate(input, mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.TENANT_MISMATCH)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 22: Omitted required trip produces TRIP_OMITTED (F-07)")
  void mutationOmittedRequiredTripProducesTripOmitted() {
    Trip missingTrip =
        new Trip(
            TenantId.of("tenant-1"),
            TripId.of("T-MISSING-OMITTED"),
            baselineTrip.startTs(),
            pickupLoc,
            dropoffLoc,
            10_000,
            1200,
            baselineTrip.latestFinishTs(),
            "EMP-MISSING");
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
            .addCharger(charger1)
            .addVehicle(cab1, hub1Loc, 50_000L)
            .addTrip(baselineTrip)
            .addTrip(missingTrip)
            .build();

    ValidationReport report = validator.validate(input, baselineValidPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.TRIP_OMITTED)).isNotEmpty();
  }

  @Test
  @DisplayName(
      "Mutation 24: Charging session cost altered by 1 micro-unit produces COST_MISMATCH (F-09)")
  void mutationSessionCostAlteredByOneMicroUnit() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tStart = t0.plusSeconds(2400);
    Instant tEnd = tStart.plusSeconds(1080);
    UtcTariffWindow tariffWindow =
        new UtcTariffWindow(hub1.id(), t0, t0.plusSeconds(7200), 100_000L);
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
            .addCharger(charger1)
            .addUtcTariff(tariffWindow)
            .addVehicle(cab1, hub1Loc, 50_000L)
            .addTrip(baselineTrip)
            .build();

    ChargingSession mutatedSession =
        new ChargingSession(
            "SESS-BASE",
            cab1.id(),
            hub1.id(),
            charger1.id(),
            tStart,
            tEnd,
            47_000L,
            55_000L,
            800_001L);
    TripAssignment assign =
        new TripAssignment(
            baselineTrip,
            cab1.id(),
            baselineTrip.startTs(),
            baselineTrip.startTs().plusSeconds(1200),
            5_000,
            600,
            750L,
            49_250L,
            47_750L);
    VehicleSchedule sched =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(assign, mutatedSession));
    FleetPlan mutatedPlan =
        new FleetPlan(
            "P-COST-MUT",
            "tenant-1",
            baselineValidPlan.horizonStart(),
            baselineValidPlan.horizonEnd(),
            List.of(sched),
            List.of(),
            999L,
            "hash");

    ValidationReport report = validator.validate(input, mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.COST_MISMATCH)).isNotEmpty();
  }
}
