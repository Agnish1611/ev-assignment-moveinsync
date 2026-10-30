package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.distance.MatrixDistanceProvider;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Single-fault mutation tests for invariant H3 (trip timing and routing feasibility). */
class H3TripTimingMutationTest extends ValidatorMutationTestFixture {

  @Test
  @DisplayName("Mutation 4: Shifting trip pickup by +1 second is caught (H3)")
  void mutationShiftPickupBy1SecondLate() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant shiftedPickup = baselineTrip.startTs().plusSeconds(1);
    Instant dropoff = shiftedPickup.plusSeconds(1200);

    TripAssignment mutatedAssign =
        new TripAssignment(
            baselineTrip, cab1.id(), shiftedPickup, dropoff, 5_000, 600, 750L, 49_250L, 47_750L);
    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(mutatedAssign));
    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-4",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-4");

    ValidationReport report = validator.validate(baseInput(mutatedPlan), mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H3_TRIP_TIMING)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 5: Shifting trip pickup by -1 second early is caught (H3)")
  void mutationShiftPickupBy1SecondEarly() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant shiftedPickup = baselineTrip.startTs().minusSeconds(1);
    Instant dropoff = shiftedPickup.plusSeconds(1200);

    TripAssignment mutatedAssign =
        new TripAssignment(
            baselineTrip, cab1.id(), shiftedPickup, dropoff, 5_000, 600, 750L, 49_250L, 47_750L);
    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(mutatedAssign));
    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-5",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-5");

    ValidationReport report = validator.validate(baseInput(mutatedPlan), mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H3_TRIP_TIMING)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 6: Shifting dropoff by 1 second past deadline is caught (H3)")
  void mutationShiftDropoffPastDeadline() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant pickup = baselineTrip.startTs();
    Instant lateDropoff = baselineTrip.latestFinishTs().plusSeconds(1);

    TripAssignment mutatedAssign =
        new TripAssignment(
            baselineTrip, cab1.id(), pickup, lateDropoff, 5_000, 600, 750L, 49_250L, 47_750L);
    VehicleSchedule mutatedSchedule =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(mutatedAssign));
    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-6",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(mutatedSchedule),
            List.of(),
            999L,
            "hash-mut-6");

    ValidationReport report = validator.validate(baseInput(mutatedPlan), mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H3_TRIP_TIMING)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 20: Missing routing distance produces ROUTE_UNAVAILABLE (F-05)")
  void mutationMissingRoutingDistanceProducesRouteUnavailable() {
    Location isolatedLoc = Location.of("LOC-ISOLATED", "Isolated Island");
    Trip isolatedTrip =
        new Trip(
            TenantId.of("tenant-1"),
            TripId.of("T-ISOLATED"),
            baselineTrip.startTs(),
            isolatedLoc,
            dropoffLoc,
            5000,
            600,
            baselineTrip.latestFinishTs(),
            "EMP-ISO");
    DistanceProvider emptyProvider = new MatrixDistanceProvider(Map.of());
    ValidationInput input =
        ValidationInput.builder()
            .tenantId(TenantId.of("tenant-1"))
            .horizon(baselineValidPlan.horizonStart(), baselineValidPlan.horizonEnd())
            .consumptionModel(new ConstantConsumptionModel())
            .distanceProvider(emptyProvider)
            .chargingCurve(context.chargingCurve())
            .batteryFloorPercentage(context.batteryFloorPercentage())
            .enforceReserveToHub(false)
            .addHub(hub1)
            .addCharger(charger1)
            .addVehicle(cab1, hub1Loc, 50_000L)
            .addTrip(isolatedTrip)
            .build();

    TripAssignment assign =
        new TripAssignment(
            isolatedTrip,
            cab1.id(),
            isolatedTrip.startTs(),
            isolatedTrip.plannedDropoffTs(),
            5000,
            600,
            0L,
            50_000L,
            49_000L);
    VehicleSchedule sched =
        new VehicleSchedule(
            cab1, hub1Loc, 50_000L, baselineValidPlan.horizonStart(), List.of(assign));
    FleetPlan plan =
        new FleetPlan(
            "P-ROUTING-MUT",
            "tenant-1",
            baselineValidPlan.horizonStart(),
            baselineValidPlan.horizonEnd(),
            List.of(sched),
            List.of(),
            999L,
            "hash");

    ValidationReport report = validator.validate(input, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.ROUTE_UNAVAILABLE)).isNotEmpty();
  }
}
