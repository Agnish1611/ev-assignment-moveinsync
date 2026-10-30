package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.charging.ChargingCurve;
import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
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

/** Bidirectional claim mismatch tests (+1 Wh and -1 Wh) for trip arrival and departure SoC. */
class SocTripClaimMismatchValidatorTest {

  private final Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
  private final TenantId tenant = TenantId.of("tenant-1");
  private final Location hubLoc = Location.of("LOC-HUB", "Hub Loc");
  private final Location pickLoc = Location.of("LOC-PICK", "Pick Loc");
  private final Location dropLoc = Location.of("LOC-DROP", "Drop Loc");

  private final Hub hub = Hub.of("tenant-1", "HUB-1", "Main Hub", hubLoc, ZoneId.of("UTC"), 1);
  private final Charger charger = Charger.of("tenant-1", "CHG-1", "HUB-1", 50);
  private final Vehicle cab =
      new Vehicle(TenantId.of("tenant-1"), VehicleId.of("CAB-01"), 60_000L, 150L, 50);
  private final Trip trip =
      new Trip(
          TenantId.of("tenant-1"),
          TripId.of("TRIP-01"),
          t0.plusSeconds(1800),
          pickLoc,
          dropLoc,
          10_000,
          1200,
          t0.plusSeconds(5400),
          "E-1");

  private final DistanceProvider distanceProvider = new ConstantSpeedDistanceProvider(5000, 10);
  private final ConstantConsumptionModel consumptionModel = new ConstantConsumptionModel();
  private final ChargingCurve chargingCurve = ChargingCurve.defaultCurve();

  private ValidationInput input;
  private PlanValidator validator;

  @BeforeEach
  void setUp() {
    validator = new PlanValidator();
    input =
        ValidationInput.builder()
            .tenantId(tenant)
            .horizon(t0, t0.plusSeconds(14400))
            .addHub(hub)
            .addCharger(charger)
            .addVehicle(cab, hubLoc, 50_000L)
            .addTrip(trip)
            .addUtcTariff(new UtcTariffWindow(hub.id(), t0, t0.plusSeconds(14400), 100_000L))
            .distanceProvider(distanceProvider)
            .consumptionModel(consumptionModel)
            .chargingCurve(chargingCurve)
            .batteryFloorPercentage(15)
            .enforceReserveToHub(true)
            .build();
  }

  @Test
  @DisplayName("Claimed arrival SoC +1 Wh produces CLAIMED_SOC_MISMATCH")
  void arrivalAtPickupSocPlus1WhProducesClaimedSocMismatch() {
    TripAssignment assign =
        new TripAssignment(
            trip,
            cab.id(),
            trip.startTs(),
            trip.startTs().plusSeconds(1200),
            5000,
            600,
            750L,
            49_251L,
            47_750L);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of(assign));
    FleetPlan plan =
        new FleetPlan(
            "P-ARR-PLUS",
            tenant.value(),
            t0,
            t0.plusSeconds(14400),
            List.of(sched),
            List.of(),
            1L,
            "h");

    ValidationReport report = validator.validate(input, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CLAIMED_SOC_MISMATCH)).isNotEmpty();
  }

  @Test
  @DisplayName("Claimed arrival SoC -1 Wh produces CLAIMED_SOC_MISMATCH")
  void arrivalAtPickupSocMinus1WhProducesClaimedSocMismatch() {
    TripAssignment assign =
        new TripAssignment(
            trip,
            cab.id(),
            trip.startTs(),
            trip.startTs().plusSeconds(1200),
            5000,
            600,
            750L,
            49_249L,
            47_750L);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of(assign));
    FleetPlan plan =
        new FleetPlan(
            "P-ARR-MINUS",
            tenant.value(),
            t0,
            t0.plusSeconds(14400),
            List.of(sched),
            List.of(),
            1L,
            "h");

    ValidationReport report = validator.validate(input, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CLAIMED_SOC_MISMATCH)).isNotEmpty();
  }

  @Test
  @DisplayName("Claimed departure SoC +1 Wh produces CLAIMED_SOC_MISMATCH")
  void departureAfterDropoffSocPlus1WhProducesClaimedSocMismatch() {
    TripAssignment assign =
        new TripAssignment(
            trip,
            cab.id(),
            trip.startTs(),
            trip.startTs().plusSeconds(1200),
            5000,
            600,
            750L,
            49_250L,
            47_751L);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of(assign));
    FleetPlan plan =
        new FleetPlan(
            "P-DEP-PLUS",
            tenant.value(),
            t0,
            t0.plusSeconds(14400),
            List.of(sched),
            List.of(),
            1L,
            "h");

    ValidationReport report = validator.validate(input, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CLAIMED_SOC_MISMATCH)).isNotEmpty();
  }

  @Test
  @DisplayName("Claimed departure SoC -1 Wh produces CLAIMED_SOC_MISMATCH")
  void departureAfterDropoffSocMinus1WhProducesClaimedSocMismatch() {
    TripAssignment assign =
        new TripAssignment(
            trip,
            cab.id(),
            trip.startTs(),
            trip.startTs().plusSeconds(1200),
            5000,
            600,
            750L,
            49_250L,
            47_749L);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of(assign));
    FleetPlan plan =
        new FleetPlan(
            "P-DEP-MINUS",
            tenant.value(),
            t0,
            t0.plusSeconds(14400),
            List.of(sched),
            List.of(),
            1L,
            "h");

    ValidationReport report = validator.validate(input, plan);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CLAIMED_SOC_MISMATCH)).isNotEmpty();
  }
}
