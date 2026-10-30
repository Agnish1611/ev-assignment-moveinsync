package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Single-fault mutation tests for invariant H1 (battery floor) and Decision D2 (energy
 * conservation).
 */
class H1BatteryFloorMutationTest extends ValidatorMutationTestFixture {

  @Test
  @DisplayName("Mutation 1: Shaving 1 Wh below 15% floor at pickup is caught (H1)")
  void mutationShave1WhBelowFloorAtPickup() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);
    Instant tDropoff = tPickup.plusSeconds(1200);

    TripAssignment mutatedAssign =
        new TripAssignment(
            baselineTrip, cab1.id(), tPickup, tDropoff, 5_000, 600, 750L, 8_999L, 7_499L);
    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, hub1Loc, 9_749L, t0, List.of(mutatedAssign));
    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-1",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-1");

    ValidationReport report = validator.validate(baseInput(mutatedPlan), mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H1_BATTERY_FLOOR)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 2: Shaving 1 Wh below 15% floor at dropoff is caught (H1)")
  void mutationShave1WhBelowFloorAtDropoff() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);
    Instant tDropoff = tPickup.plusSeconds(1200);

    TripAssignment mutatedAssign =
        new TripAssignment(
            baselineTrip, cab1.id(), tPickup, tDropoff, 5_000, 600, 750L, 10_499L, 8_999L);
    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, hub1Loc, 11_249L, t0, List.of(mutatedAssign));
    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-2",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-2");

    ValidationReport report = validator.validate(baseInput(mutatedPlan), mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H1_BATTERY_FLOOR)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 12: Raising claimed departure SoC after dropoff by 1 Wh is caught (D2)")
  void mutationRaiseDepartureSocAfterDropoffBy1Wh() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);
    Instant tDropoff = tPickup.plusSeconds(1200);

    TripAssignment forgedAssign =
        new TripAssignment(
            baselineTrip, cab1.id(), tPickup, tDropoff, 5_000, 600, 750L, 49_250L, 47_751L);
    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(forgedAssign));
    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-12",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-12");

    ValidationReport report = validator.validate(baseInput(mutatedPlan), mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CLAIMED_SOC_MISMATCH)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 13: Raising session start SoC above arrival SoC at hub is caught (D2)")
  void mutationRaiseSessionStartSocAboveArrivalSocAtHub() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);
    Instant tDropoff = tPickup.plusSeconds(1200);
    TripAssignment assign =
        new TripAssignment(
            baselineTrip, cab1.id(), tPickup, tDropoff, 5_000, 600, 750L, 49_250L, 47_750L);
    Instant tChargeStart = tDropoff.plusSeconds(600);
    Instant tChargeEnd = tChargeStart.plusSeconds(1080);

    ChargingSession forgedSession =
        new ChargingSession(
            "SESS-FORGED-START",
            cab1.id(),
            hub1.id(),
            charger1.id(),
            tChargeStart,
            tChargeEnd,
            47_001L,
            55_000L,
            750_000L);
    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(assign, forgedSession));
    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-13",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-13");

    ValidationReport report = validator.validate(baseInput(mutatedPlan), mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CLAIMED_SOC_MISMATCH)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 14: Phantom energy scenario is caught and killed (D2)")
  void mutationPhantomEnergyInjectionAtHub() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tChargeStart = t0.plusSeconds(600);
    Instant tChargeEnd = tChargeStart.plusSeconds(1080);

    ChargingSession phantomSession =
        new ChargingSession(
            "SESS-PHANTOM",
            cab1.id(),
            hub1.id(),
            charger1.id(),
            tChargeStart,
            tChargeEnd,
            50_000L,
            55_000L,
            750_000L);
    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, pickupLoc, 10_750L, t0, List.of(phantomSession));
    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-14",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-14");

    ValidationReport report = validator.validate(baseInput(mutatedPlan), mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CLAIMED_SOC_MISMATCH)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 15: Negative claimed departure SoC is caught with SOC_OUT_OF_RANGE (F-04)")
  void mutationNegativeDepartureSocIsCaught() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);
    Instant tDropoff = tPickup.plusSeconds(1200);

    TripAssignment forgedAssign =
        new TripAssignment(
            baselineTrip, cab1.id(), tPickup, tDropoff, 5_000, 600, 750L, 49_250L, -500L);
    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(forgedAssign));
    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-15",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-15");

    ValidationReport report = validator.validate(baseInput(mutatedPlan), mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.SOC_OUT_OF_RANGE)).isNotEmpty();
  }
}
