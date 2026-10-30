package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Single-fault mutation tests for invariants H5 (reserve to hub) and H6 (charging curve). */
class H5ReserveAndH6CurveMutationTest extends ValidatorMutationTestFixture {

  @Test
  @DisplayName("Mutation 9: Shaving reserve-to-hub charge below floor is caught (H5)")
  void mutationShaveReserveToHubChargeBelowFloor() {
    Location isolatedDropoff = Location.of("LOC-ISOLATED", "Remote Location");
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);

    Trip trip =
        new Trip(
            TenantId.of("tenant-1"),
            TripId.of("TRIP-ISOLATED"),
            tPickup,
            pickupLoc,
            isolatedDropoff,
            10_000,
            1200,
            tPickup.plusSeconds(1800),
            "EMP-MUT-09");

    TripAssignment assign =
        new TripAssignment(
            trip, cab1.id(), tPickup, tPickup.plusSeconds(1200), 5_000, 600, 750L, 10_600L, 9_100L);
    VehicleSchedule schedule = new VehicleSchedule(cab1, hub1Loc, 11_350L, t0, List.of(assign));

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-9",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(schedule),
            List.of(),
            999L,
            "hash-mut-9");

    ValidationInput input =
        ValidationInput.builder()
            .tenantId(TenantId.of("tenant-1"))
            .horizon(t0, t0.plusSeconds(7200))
            .consumptionModel(context.consumptionModel())
            .distanceProvider(context.distanceProvider())
            .chargingCurve(context.chargingCurve())
            .batteryFloorPercentage(context.batteryFloorPercentage())
            .enforceReserveToHub(true)
            .addHub(hub1)
            .addCharger(charger1)
            .addVehicle(cab1, hub1Loc, 11_350L)
            .addTrip(trip)
            .build();

    ValidationReport report = validator.validate(input, mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H5_RESERVE_TO_HUB)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 10: Shaving 1 second from required charging duration is caught (H6)")
  void mutationShave1SecondFromChargingDuration() {
    Instant tStart = Instant.parse("2026-10-30T08:40:00Z");
    Instant tEndShaved = tStart.plusSeconds(1079);

    ChargingSession shavedSession =
        new ChargingSession(
            "SESS-SHAVED-1S",
            cab1.id(),
            hub1.id(),
            charger1.id(),
            tStart,
            tEndShaved,
            47_000L,
            55_000L,
            750_000L);

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

    VehicleSchedule schedule =
        new VehicleSchedule(
            cab1,
            hub1Loc,
            50_000L,
            baselineValidPlan.horizonStart(),
            List.of(assign, shavedSession));

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-10",
            "tenant-1",
            baselineValidPlan.horizonStart(),
            baselineValidPlan.horizonEnd(),
            List.of(schedule),
            List.of(),
            999L,
            "hash-mut-10");

    ValidationReport report = validator.validate(baseInput(mutatedPlan), mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H6_CHARGING_CURVE)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 11: Target SoC inflated by 1 Wh without duration increase is caught (H6)")
  void mutationInflateTargetSocBy1Wh() {
    Instant tStart = Instant.parse("2026-10-30T08:40:00Z");
    Instant tEnd = tStart.plusSeconds(1080);

    ChargingSession inflatedSession =
        new ChargingSession(
            "SESS-INFLATED-1WH",
            cab1.id(),
            hub1.id(),
            charger1.id(),
            tStart,
            tEnd,
            47_000L,
            55_001L,
            750_000L);

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

    VehicleSchedule schedule =
        new VehicleSchedule(
            cab1,
            hub1Loc,
            50_000L,
            baselineValidPlan.horizonStart(),
            List.of(assign, inflatedSession));

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-11",
            "tenant-1",
            baselineValidPlan.horizonStart(),
            baselineValidPlan.horizonEnd(),
            List.of(schedule),
            List.of(),
            999L,
            "hash-mut-11");

    ValidationReport report = validator.validate(baseInput(mutatedPlan), mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H6_CHARGING_CURVE)).isNotEmpty();
    assertThat(report.violationsFor(RuleId.CLAIMED_SOC_MISMATCH)).isNotEmpty();
  }
}
