package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 10,000-trial fuzz testing verifying that {@link PlanValidator#validate(ValidationInput,
 * FleetPlan)} is a total function (never throws unhandled exceptions) and strictly rejects
 * malformed inputs.
 */
class PlanValidatorFuzzTest {

  private static final int FUZZ_TRIALS = 10_000;

  @Test
  @DisplayName(
      "10,000-trial fuzz test: validator never throws and always produces a report, rejecting malformed plans")
  void fuzzedPlansNeverThrowAndRejectMalformed() {
    Random rng = new Random(1337);
    TenantId tenant = TenantId.of("tenant-fuzz");
    Location hubLoc = Location.of("LOC-HUB", "Hub");
    Location pickLoc = Location.of("LOC-PICK", "Pick");
    Location dropLoc = Location.of("LOC-DROP", "Drop");
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");

    Hub hub = Hub.of("tenant-fuzz", "HUB-1", "H1", hubLoc, ZoneId.of("UTC"), 2);
    Charger charger = Charger.of("tenant-fuzz", "CHG-1", "HUB-1", 50);
    Vehicle cab = new Vehicle(tenant, VehicleId.of("CAB-01"), 60_000L, 150L, 50);
    DistanceProvider dist = new ConstantSpeedDistanceProvider(5000, 10);
    Trip baseTrip =
        new Trip(
            tenant,
            TripId.of("TRIP-01"),
            t0.plusSeconds(1800),
            pickLoc,
            dropLoc,
            5000,
            500,
            t0.plusSeconds(3600),
            "EMP-1");

    ValidationInput input =
        ValidationInput.builder()
            .tenantId(tenant)
            .horizon(t0, t0.plusSeconds(86400))
            .addHub(hub)
            .addCharger(charger)
            .addVehicle(cab, hubLoc, 50_000L)
            .addTrip(baseTrip)
            .distanceProvider(dist)
            .consumptionModel(new ConstantConsumptionModel())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(false)
            .build();

    PlanValidator validator = new PlanValidator();

    int malformedCount = 0;
    int validCount = 0;

    for (int trial = 0; trial < FUZZ_TRIALS; trial++) {
      int mutationType = rng.nextInt(6);
      boolean isMalformed = mutationType != 0;

      FleetPlan candidate;
      switch (mutationType) {
        case 1 -> {
          // Negative claimed initial SoC
          VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, -5000L, t0, List.of());
          candidate =
              new FleetPlan(
                  "P-FUZZ-" + trial,
                  tenant.value(),
                  t0,
                  t0.plusSeconds(86400),
                  List.of(sched),
                  List.of(),
                  1L,
                  "h");
          malformedCount++;
        }
        case 2 -> {
          // Out of range target SoC (exceeding battery capacity)
          ChargingSession badSession =
              new ChargingSession(
                  "S-" + trial,
                  cab.id(),
                  hub.id(),
                  charger.id(),
                  t0.plusSeconds(100),
                  t0.plusSeconds(1000),
                  20_000L,
                  999_999L,
                  500_000L);
          VehicleSchedule sched =
              new VehicleSchedule(cab, hubLoc, 20_000L, t0, List.of(badSession));
          candidate =
              new FleetPlan(
                  "P-FUZZ-" + trial,
                  tenant.value(),
                  t0,
                  t0.plusSeconds(86400),
                  List.of(sched),
                  List.of(),
                  1L,
                  "h");
          malformedCount++;
        }
        case 3 -> {
          // startSocWh > targetSocWh (illogical backwards charge)
          ChargingSession badSession =
              new ChargingSession(
                  "S-" + trial,
                  cab.id(),
                  hub.id(),
                  charger.id(),
                  t0.plusSeconds(100),
                  t0.plusSeconds(1000),
                  40_000L,
                  20_000L,
                  500_000L);
          VehicleSchedule sched =
              new VehicleSchedule(cab, hubLoc, 40_000L, t0, List.of(badSession));
          candidate =
              new FleetPlan(
                  "P-FUZZ-" + trial,
                  tenant.value(),
                  t0,
                  t0.plusSeconds(86400),
                  List.of(sched),
                  List.of(),
                  1L,
                  "h");
          malformedCount++;
        }
        case 4 -> {
          // Phantom vehicle not in ground truth
          Vehicle phantom =
              new Vehicle(tenant, VehicleId.of("CAB-PHANTOM-" + trial), 60_000L, 150L, 50);
          VehicleSchedule sched = new VehicleSchedule(phantom, hubLoc, 50_000L, t0, List.of());
          candidate =
              new FleetPlan(
                  "P-FUZZ-" + trial,
                  tenant.value(),
                  t0,
                  t0.plusSeconds(86400),
                  List.of(sched),
                  List.of(),
                  1L,
                  "h");
          malformedCount++;
        }
        case 5 -> {
          // Forged trip pickup time
          TripAssignment assign =
              new TripAssignment(
                  baseTrip,
                  cab.id(),
                  t0.plusSeconds(9999),
                  t0.plusSeconds(10500),
                  5000,
                  500,
                  750L,
                  49_250L,
                  48_500L);
          VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of(assign));
          candidate =
              new FleetPlan(
                  "P-FUZZ-" + trial,
                  tenant.value(),
                  t0,
                  t0.plusSeconds(86400),
                  List.of(sched),
                  List.of(),
                  1L,
                  "h");
          malformedCount++;
        }
        default -> {
          // Valid idle plan
          VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of());
          candidate =
              new FleetPlan(
                  "P-FUZZ-" + trial,
                  tenant.value(),
                  t0,
                  t0.plusSeconds(86400),
                  List.of(sched),
                  List.of(
                      new com.moveinsync.ev.domain.schedule.UnassignedTrip(
                          baseTrip,
                          com.moveinsync.ev.domain.schedule.UnassignedReasonCode
                              .NO_CAB_FREE_IN_TIME_WINDOW,
                          "Unassigned in fuzz trial")),
                  1L,
                  "h");
          validCount++;
        }
      }

      ValidationReport report = validator.validate(input, candidate);
      assertThat(report).as("Validator must always return a report (never null)").isNotNull();

      if (isMalformed) {
        assertThat(report.isValid())
            .as("Malformed trial %d of type %d must produce invalid report", trial, mutationType)
            .isFalse();
        assertThat(report.violations()).isNotEmpty();
      } else {
        assertThat(report.isValid()).as("Valid trial %d must produce valid report", trial).isTrue();
      }
    }

    assertThat(malformedCount).isGreaterThan(7000);
    assertThat(validCount).isGreaterThan(1000);
  }
}
