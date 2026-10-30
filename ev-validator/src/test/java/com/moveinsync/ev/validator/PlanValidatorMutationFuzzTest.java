package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Property-fuzz tests: 1. Generated known-valid plans are ALWAYS VALID (thousands of trials). 2.
 * Injected corruption of a random field is ALWAYS INVALID with the exact expected RuleId.
 */
class PlanValidatorMutationFuzzTest {

  private static final int KNOWN_VALID_TRIALS = 2_000;
  private static final int CORRUPTION_TRIALS = 3_000;

  @Test
  @DisplayName(
      "Fuzz: Generated known-valid plans are always reported VALID across thousands of trials")
  void generatedKnownValidPlansAreAlwaysValid() {
    long seed = 987654321L;
    Random rng = new Random(seed);
    PlanValidator validator = new PlanValidator();

    for (int trial = 0; trial < KNOWN_VALID_TRIALS; trial++) {
      PlanValidatorFuzzFixture f = new PlanValidatorFuzzFixture(trial, rng);
      ValidationReport report = validator.validate(f.input, f.validPlan);
      assertThat(report.isValid())
          .as(
              "Valid plan must be VALID on trial %d (seed=%d). Violations: %s",
              trial, seed, report.violations())
          .isTrue();
      assertThat(report.violations()).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "Fuzz: Valid plan + exactly one random field corruption is INVALID with expected RuleId")
  void singleRandomFieldCorruptionAlwaysProducesExpectedViolation() {
    long seed = 123456789L;
    Random rng = new Random(seed);
    PlanValidator validator = new PlanValidator();

    for (int trial = 0; trial < CORRUPTION_TRIALS; trial++) {
      PlanValidatorFuzzFixture f = new PlanValidatorFuzzFixture(trial, rng);
      int mutationType = rng.nextInt(7);
      RuleId expectedRule;
      FleetPlan corruptPlan;

      switch (mutationType) {
        case 0 -> {
          // 1. Shave charging duration by 1s -> H6_CHARGING_CURVE
          expectedRule = RuleId.H6_CHARGING_CURVE;
          ChargingSession s = f.session;
          ChargingSession corruptSession =
              new ChargingSession(
                  s.sessionId(),
                  s.vehicleId(),
                  s.hubId(),
                  s.chargerId(),
                  s.startTime(),
                  s.endTime().minusSeconds(1),
                  s.startSocWh(),
                  s.targetSocWh(),
                  s.costMicros());
          VehicleSchedule sched =
              new VehicleSchedule(f.cab, f.depot, f.startSoc, f.t0, List.of(corruptSession));
          corruptPlan = f.planWithSchedule(sched);
        }
        case 1 -> {
          // 2. Shift trip pickup late by 1s -> H3_TRIP_TIMING
          expectedRule = RuleId.H3_TRIP_TIMING;
          TripAssignment a = f.assignment;
          TripAssignment corruptAssign =
              new TripAssignment(
                  f.trip,
                  a.vehicleId(),
                  a.pickupTime().plusSeconds(1),
                  a.dropoffTime().plusSeconds(1),
                  a.deadheadDistanceMeters(),
                  a.deadheadDurationSeconds(),
                  a.deadheadEnergyWh(),
                  a.arrivalAtPickupSocWh(),
                  a.departureAfterDropoffSocWh());
          VehicleSchedule sched =
              new VehicleSchedule(f.cab, f.depot, f.startSoc, f.t0, List.of(corruptAssign));
          corruptPlan = f.planWithSchedule(sched);
        }
        case 2 -> {
          // 3. Double assign trip to another cab -> H4_DOUBLE_ASSIGNMENT
          expectedRule = RuleId.H4_DOUBLE_ASSIGNMENT;
          Vehicle cab2 = new Vehicle(f.tenant, VehicleId.of("CAB-02"), 60_000L, 150L, 50);
          VehicleSchedule sched2 =
              new VehicleSchedule(cab2, f.depot, f.startSoc, f.t0, List.of(f.assignment));
          corruptPlan =
              new FleetPlan(
                  "P-CORRUPT-" + trial,
                  f.tenant.value(),
                  f.t0,
                  f.t0.plusSeconds(86400),
                  List.of(f.schedule, sched2),
                  List.of(),
                  1L,
                  "h");
        }
        case 3 -> {
          // 4. Alter session cost by 1 micro-unit -> COST_MISMATCH
          expectedRule = RuleId.COST_MISMATCH;
          ChargingSession s = f.session;
          long corruptedCost = rng.nextBoolean() ? s.costMicros() + 1 : s.costMicros() - 1;
          ChargingSession corruptSession =
              new ChargingSession(
                  s.sessionId(),
                  s.vehicleId(),
                  s.hubId(),
                  s.chargerId(),
                  s.startTime(),
                  s.endTime(),
                  s.startSocWh(),
                  s.targetSocWh(),
                  corruptedCost);
          VehicleSchedule sched =
              new VehicleSchedule(f.cab, f.depot, f.startSoc, f.t0, List.of(corruptSession));
          corruptPlan = f.planWithSchedule(sched);
        }
        case 4 -> {
          // 5. Foreign tenant on unassigned trip -> TENANT_MISMATCH
          expectedRule = RuleId.TENANT_MISMATCH;
          Trip foreignTrip =
              new Trip(
                  TenantId.of("FOREIGN"),
                  TripId.of("TRIP-FOR-" + trial),
                  f.t0.plusSeconds(300),
                  f.pickup,
                  f.dropoff,
                  5000,
                  600,
                  f.t0.plusSeconds(3600),
                  "E");
          corruptPlan =
              new FleetPlan(
                  "P-CORRUPT-" + trial,
                  f.tenant.value(),
                  f.t0,
                  f.t0.plusSeconds(86400),
                  List.of(f.schedule),
                  List.of(
                      UnassignedTrip.of(
                          foreignTrip,
                          UnassignedReasonCode.NO_CAB_FREE_IN_TIME_WINDOW,
                          "unassigned")),
                  1L,
                  "h");
        }
        case 5 -> {
          // 6. Shave 1 Wh from departure SoC after dropoff -> CLAIMED_SOC_MISMATCH
          expectedRule = RuleId.CLAIMED_SOC_MISMATCH;
          TripAssignment a = f.assignment;
          TripAssignment corruptAssign =
              new TripAssignment(
                  f.trip,
                  a.vehicleId(),
                  a.pickupTime(),
                  a.dropoffTime(),
                  a.deadheadDistanceMeters(),
                  a.deadheadDurationSeconds(),
                  a.deadheadEnergyWh(),
                  a.arrivalAtPickupSocWh(),
                  a.departureAfterDropoffSocWh() - 1L);
          VehicleSchedule sched =
              new VehicleSchedule(f.cab, f.depot, f.startSoc, f.t0, List.of(corruptAssign));
          corruptPlan = f.planWithSchedule(sched);
        }
        default -> {
          // 7. Activity outside horizon -> ACTIVITY_OUTSIDE_HORIZON
          expectedRule = RuleId.ACTIVITY_OUTSIDE_HORIZON;
          Trip outsideTrip =
              new Trip(
                  f.tenant,
                  TripId.of("TRIP-OUT-" + trial),
                  f.t0.minusSeconds(100),
                  f.pickup,
                  f.dropoff,
                  5000,
                  600,
                  f.t0.plusSeconds(3600),
                  "E");
          TripAssignment corruptAssign =
              new TripAssignment(
                  outsideTrip,
                  f.cab.id(),
                  f.t0.minusSeconds(100),
                  f.t0.plusSeconds(500),
                  0,
                  0,
                  0L,
                  f.startSoc,
                  f.startSoc - 750L);
          VehicleSchedule sched =
              new VehicleSchedule(
                  f.cab, f.pickup, f.startSoc, f.t0.minusSeconds(100), List.of(corruptAssign));
          corruptPlan = f.planWithSchedule(sched);
        }
      }

      ValidationReport report = validator.validate(f.input, corruptPlan);
      assertThat(report.isValid())
          .as("Trial %d (seed=%d, mutation=%d) must be INVALID", trial, seed, mutationType)
          .isFalse();
      assertThat(report.violationsFor(expectedRule))
          .as(
              "Trial %d (seed=%d, mutation=%d) must contain %s. Got: %s",
              trial, seed, mutationType, expectedRule, report.violations())
          .isNotEmpty();
    }
  }
}
