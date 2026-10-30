package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.charging.ChargingCurve;
import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ValidationInputTest {

  private TenantId tenantId;
  private Location hubLoc;
  private Location pickLoc;
  private Location dropLoc;
  private Hub hub;
  private Charger charger;
  private Vehicle vehicle;
  private Trip trip;
  private Instant t0;
  private ValidationInput input;
  private PlanValidator validator;

  @BeforeEach
  void setUp() {
    tenantId = TenantId.of("tenant-moveinsync");
    hubLoc = Location.of("LOC-HUB", "Hub A");
    pickLoc = Location.of("LOC-PICK", "Pickup");
    dropLoc = Location.of("LOC-DROP", "Dropoff");

    hub = Hub.of("tenant-moveinsync", "HUB-1", "Hub A", hubLoc, ZoneId.of("UTC"), 1);
    charger = Charger.of("tenant-moveinsync", "CHG-1", "HUB-1", 50);
    vehicle = new Vehicle(tenantId, VehicleId.of("CAB-01"), 60_000L, 150L, 50);

    t0 = Instant.parse("2026-10-30T08:00:00Z");
    trip =
        new Trip(
            tenantId,
            TripId.of("TRIP-01"),
            t0.plusSeconds(600),
            pickLoc,
            dropLoc,
            10_000,
            1200,
            t0.plusSeconds(2400),
            "EMP-01");

    input =
        ValidationInput.builder()
            .tenantId(tenantId)
            .horizon(t0, t0.plusSeconds(86400))
            .addHub(hub)
            .addCharger(charger)
            .addVehicle(vehicle, hubLoc, 50_000L)
            .addTrip(trip)
            .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
            .consumptionModel(new ConstantConsumptionModel())
            .chargingCurve(ChargingCurve.defaultCurve())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(true)
            .build();

    validator = new PlanValidator();
  }

  @Test
  @DisplayName("Candidate referencing unknown vehicle ID produces UNKNOWN_VEHICLE violation")
  void unknownVehicleId() {
    Vehicle unknownVehicle = new Vehicle(tenantId, VehicleId.of("CAB-GHOST"), 60_000L, 150L, 50);
    VehicleSchedule sched = new VehicleSchedule(unknownVehicle, hubLoc, 50_000L, t0, List.of());
    FleetPlan candidate =
        new FleetPlan(
            "P-UNK-CAB", tenantId, t0, t0.plusSeconds(3600), List.of(sched), List.of(), 1L, "hash");

    ValidationReport report = validator.validate(input, candidate);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.UNKNOWN_VEHICLE)).isNotEmpty();
  }

  @Test
  @DisplayName("Candidate referencing unknown trip ID produces UNKNOWN_TRIP violation")
  void unknownTripId() {
    Trip ghostTrip =
        new Trip(
            tenantId,
            TripId.of("TRIP-GHOST"),
            t0.plusSeconds(600),
            pickLoc,
            dropLoc,
            5000,
            600,
            t0.plusSeconds(1800),
            "EMP-GHOST");
    TripAssignment assign =
        new TripAssignment(
            ghostTrip,
            vehicle.id(),
            t0.plusSeconds(600),
            t0.plusSeconds(1200),
            5000,
            600,
            750L,
            49250L,
            48500L);
    VehicleSchedule sched = new VehicleSchedule(vehicle, hubLoc, 50_000L, t0, List.of(assign));
    FleetPlan candidate =
        new FleetPlan(
            "P-UNK-TRIP",
            tenantId,
            t0,
            t0.plusSeconds(3600),
            List.of(sched),
            List.of(),
            1L,
            "hash");

    ValidationReport report = validator.validate(input, candidate);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.UNKNOWN_TRIP)).isNotEmpty();
  }

  @Test
  @DisplayName("Tenant mismatch between input and candidate produces TENANT_MISMATCH violation")
  void tenantMismatch() {
    TenantId otherTenant = TenantId.of("tenant-competitor");
    FleetPlan candidate =
        new FleetPlan(
            "P-DIFF-TENANT",
            otherTenant,
            t0,
            t0.plusSeconds(3600),
            List.of(),
            List.of(),
            1L,
            "hash");

    ValidationReport report = validator.validate(input, candidate);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.TENANT_MISMATCH)).isNotEmpty();
  }

  @Test
  @DisplayName(
      "D5 Totality guard converts unexpected runtime exceptions to VALIDATOR_INTERNAL_ERROR")
  void totalityGuardCatchesException() {
    // Passing null candidate
    ValidationReport report = validator.validate(input, null);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.VALIDATOR_INTERNAL_ERROR)).isNotEmpty();

    // Passing null input
    ValidationReport reportNullInput =
        validator.validate(
            null,
            new FleetPlan(
                "P-NPE", tenantId, t0, t0.plusSeconds(3600), List.of(), List.of(), 1L, "hash"));
    assertThat(reportNullInput.isValid()).isFalse();
    assertThat(reportNullInput.violationsFor(RuleId.VALIDATOR_INTERNAL_ERROR)).isNotEmpty();
  }

  @Test
  @DisplayName("Candidate vehicle belonging to different tenant produces TENANT_MISMATCH")
  void candidateVehicleWrongTenant() {
    TenantId otherTenant = TenantId.of("tenant-competitor");
    Vehicle otherCab = new Vehicle(otherTenant, VehicleId.of("CAB-01"), 60_000L, 150L, 50);
    VehicleSchedule sched = new VehicleSchedule(otherCab, hubLoc, 50_000L, t0, List.of());
    FleetPlan candidate =
        new FleetPlan(
            "P-DIFF-VEH-TENANT",
            tenantId,
            t0,
            t0.plusSeconds(3600),
            List.of(sched),
            List.of(),
            1L,
            "hash");

    ValidationReport report = validator.validate(input, candidate);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.TENANT_MISMATCH)).isNotEmpty();
  }

  @Test
  @DisplayName("Candidate trip belonging to different tenant produces TENANT_MISMATCH")
  void candidateTripWrongTenant() {
    TenantId otherTenant = TenantId.of("tenant-competitor");
    Trip otherTrip =
        new Trip(
            otherTenant,
            TripId.of("TRIP-01"),
            t0.plusSeconds(600),
            pickLoc,
            dropLoc,
            10_000,
            1200,
            t0.plusSeconds(2400),
            "EMP-01");
    TripAssignment assign =
        new TripAssignment(
            otherTrip,
            vehicle.id(),
            t0.plusSeconds(600),
            t0.plusSeconds(1800),
            0,
            0,
            0L,
            50_000L,
            48_500L);
    VehicleSchedule sched = new VehicleSchedule(vehicle, pickLoc, 50_000L, t0, List.of(assign));
    FleetPlan candidate =
        new FleetPlan(
            "P-DIFF-TRIP-TENANT",
            tenantId,
            t0,
            t0.plusSeconds(3600),
            List.of(sched),
            List.of(),
            1L,
            "hash");

    ValidationReport report = validator.validate(input, candidate);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.TENANT_MISMATCH)).isNotEmpty();
  }

  @Test
  @DisplayName("Candidate charging session with unknown hub produces UNKNOWN_HUB")
  void unknownHubIdInSession() {
    ChargingSession session =
        new ChargingSession(
            "S-01",
            vehicle.id(),
            HubId.of("HUB-GHOST"),
            charger.id(),
            t0.plusSeconds(100),
            t0.plusSeconds(1000),
            40_000L,
            50_000L,
            500_000L);
    VehicleSchedule sched = new VehicleSchedule(vehicle, hubLoc, 50_000L, t0, List.of(session));
    FleetPlan candidate =
        new FleetPlan(
            "P-UNK-HUB", tenantId, t0, t0.plusSeconds(3600), List.of(sched), List.of(), 1L, "hash");

    ValidationReport report = validator.validate(input, candidate);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.UNKNOWN_HUB)).isNotEmpty();
  }

  @Test
  @DisplayName("Candidate charging session with unknown charger produces UNKNOWN_CHARGER")
  void unknownChargerIdInSession() {
    ChargingSession session =
        new ChargingSession(
            "S-01",
            vehicle.id(),
            hub.id(),
            ChargerId.of("CHG-GHOST"),
            t0.plusSeconds(100),
            t0.plusSeconds(1000),
            40_000L,
            50_000L,
            500_000L);
    VehicleSchedule sched = new VehicleSchedule(vehicle, hubLoc, 50_000L, t0, List.of(session));
    FleetPlan candidate =
        new FleetPlan(
            "P-UNK-CHG", tenantId, t0, t0.plusSeconds(3600), List.of(sched), List.of(), 1L, "hash");

    ValidationReport report = validator.validate(input, candidate);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.UNKNOWN_CHARGER)).isNotEmpty();
  }
}
