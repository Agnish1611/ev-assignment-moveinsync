package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Single-fault mutation tests for invariant H4 (single vehicle, non-overlapping assignments,
 * partition completeness).
 */
class H4AssignmentMutationTest extends ValidatorMutationTestFixture {

  @Test
  @DisplayName("Mutation 7: Double-assigning a trip to two cabs is caught (H4)")
  void mutationDoubleAssignTripToTwoCabs() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = baselineTrip.startTs();
    Instant tDropoff = tPickup.plusSeconds(1200);

    TripAssignment assign1 =
        new TripAssignment(
            baselineTrip, cab1.id(), tPickup, tDropoff, 5_000, 600, 750L, 49_250L, 47_750L);
    VehicleSchedule sched1 = new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(assign1));

    TripAssignment assign2 =
        new TripAssignment(
            baselineTrip, cab2.id(), tPickup, tDropoff, 5_000, 600, 750L, 49_250L, 47_750L);
    VehicleSchedule sched2 = new VehicleSchedule(cab2, hub1Loc, 50_000L, t0, List.of(assign2));

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-7",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(sched1, sched2),
            List.of(),
            999L,
            "hash-mut-7");

    ValidationInput input =
        ValidationInput.builder()
            .tenantId(TenantId.of("tenant-1"))
            .horizon(t0, t0.plusSeconds(7200))
            .consumptionModel(context.consumptionModel())
            .distanceProvider(context.distanceProvider())
            .chargingCurve(context.chargingCurve())
            .batteryFloorPercentage(context.batteryFloorPercentage())
            .enforceReserveToHub(context.enforceReserveToHub())
            .addHub(hub1)
            .addCharger(charger1)
            .addVehicle(cab1, hub1Loc, 50_000L)
            .addVehicle(cab2, hub1Loc, 50_000L)
            .addTrip(baselineTrip)
            .build();

    ValidationReport report = validator.validate(input, mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H4_DOUBLE_ASSIGNMENT)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 8: Overlapping two activities on the same cab by 1 second is caught (H4)")
  void mutationOverlapActivitiesOnSameCabBy1Second() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);
    Instant tDropoff = tPickup.plusSeconds(1200);

    Trip trip2 =
        new Trip(
            TenantId.of("tenant-1"),
            TripId.of("TRIP-2"),
            tDropoff.minusSeconds(1),
            pickupLoc,
            dropoffLoc,
            10_000,
            1200,
            tDropoff.plusSeconds(2400),
            "EMP-MUT-02");

    TripAssignment assign1 =
        new TripAssignment(
            baselineTrip, cab1.id(), tPickup, tDropoff, 5_000, 600, 750L, 49_250L, 47_750L);

    TripAssignment assign2 =
        new TripAssignment(
            trip2,
            cab1.id(),
            tDropoff.minusSeconds(1),
            tDropoff.plusSeconds(1199),
            5_000,
            600,
            750L,
            47_000L,
            45_500L);

    VehicleSchedule schedule =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(assign1, assign2));

    FleetPlan mutatedPlan =
        new FleetPlan(
            "PLAN-MUT-8",
            "tenant-1",
            t0,
            t0.plusSeconds(7200),
            List.of(schedule),
            List.of(),
            999L,
            "hash-mut-8");

    ValidationInput input =
        ValidationInput.builder()
            .tenantId(TenantId.of("tenant-1"))
            .horizon(t0, t0.plusSeconds(7200))
            .consumptionModel(context.consumptionModel())
            .distanceProvider(context.distanceProvider())
            .chargingCurve(context.chargingCurve())
            .batteryFloorPercentage(context.batteryFloorPercentage())
            .enforceReserveToHub(context.enforceReserveToHub())
            .addHub(hub1)
            .addCharger(charger1)
            .addVehicle(cab1, hub1Loc, 50_000L)
            .addTrip(baselineTrip)
            .addTrip(trip2)
            .build();

    ValidationReport report = validator.validate(input, mutatedPlan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H4_ASSIGNMENT_OVERLAP)).isNotEmpty();
  }

  @Test
  @DisplayName("Mutation 19: Unknown trip in unassignedTrips produces TRIP_UNKNOWN (F-07)")
  void mutationUnknownUnassignedTripProducesTripUnknown() {
    Trip unknownTrip =
        new Trip(
            TenantId.of("tenant-1"),
            TripId.of("T-UNKNOWN"),
            baselineTrip.startTs(),
            pickupLoc,
            dropoffLoc,
            10_000,
            1200,
            baselineTrip.latestFinishTs(),
            "EMP-UNK");
    ValidationInput input = baseInput(baselineValidPlan);
    FleetPlan planWithUnknownUnassigned =
        new FleetPlan(
            "P-UNKNOWN-UNASSIGNED",
            "tenant-1",
            baselineValidPlan.horizonStart(),
            baselineValidPlan.horizonEnd(),
            baselineValidPlan.vehicleSchedules(),
            List.of(
                UnassignedTrip.of(
                    unknownTrip, UnassignedReasonCode.NO_CAB_FREE_IN_TIME_WINDOW, "No cab")),
            999L,
            "hash");

    ValidationReport report = validator.validate(input, planWithUnknownUnassigned);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.TRIP_UNKNOWN)).isNotEmpty();
  }

  @Test
  @DisplayName(
      "Mutation 23: Trip assigned to cab and also marked unassigned produces TRIP_ASSIGNED_AND_UNASSIGNED (F-07)")
  void mutationTripAssignedAndUnassigned() {
    ValidationInput input = baseInput(baselineValidPlan);
    FleetPlan planWithDuplicate =
        new FleetPlan(
            "P-DUPLICATE-UNASSIGNED",
            "tenant-1",
            baselineValidPlan.horizonStart(),
            baselineValidPlan.horizonEnd(),
            baselineValidPlan.vehicleSchedules(),
            List.of(
                UnassignedTrip.of(
                    baselineTrip, UnassignedReasonCode.OTHER, "Conflictingly marked unassigned")),
            999L,
            "hash");

    ValidationReport report = validator.validate(input, planWithDuplicate);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.TRIP_ASSIGNED_AND_UNASSIGNED)).isNotEmpty();
  }
}
