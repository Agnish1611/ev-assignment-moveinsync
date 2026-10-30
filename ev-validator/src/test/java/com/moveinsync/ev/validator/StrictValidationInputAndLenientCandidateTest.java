package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.ChargerStatus;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
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

/**
 * Verifies Decision D16: Ground-truth types inside ValidationInput strictly validate invariants and
 * fail fast, while candidate plan models are lenient and never throw during plan construction.
 */
class StrictValidationInputAndLenientCandidateTest {

  private TenantId tenant;
  private Instant t0;
  private Location hubLoc;
  private Location pickupLoc;
  private Location dropoffLoc;
  private Hub hub;
  private Charger charger;
  private Vehicle validVehicle;
  private Trip validTrip;
  private DistanceProvider distanceProvider;

  @BeforeEach
  void setUp() {
    tenant = TenantId.of("tenant-strict");
    t0 = Instant.parse("2026-10-30T08:00:00Z");
    hubLoc = Location.of("LOC-HUB", "Hub");
    pickupLoc = Location.of("LOC-PICKUP", "Pickup");
    dropoffLoc = Location.of("LOC-DROPOFF", "Dropoff");
    hub = Hub.of(tenant.value(), "HUB-1", "Central Hub", hubLoc, ZoneId.of("UTC"), 2);
    charger = Charger.of(tenant.value(), "CHG-1", "HUB-1", 50);
    validVehicle = new Vehicle(tenant, VehicleId.of("CAB-01"), 60_000L, 150L, 50);
    validTrip =
        new Trip(
            tenant,
            TripId.of("TRIP-1"),
            t0.plusSeconds(600),
            pickupLoc,
            dropoffLoc,
            10_000,
            1200,
            t0.plusSeconds(2400),
            "EMP-1");
    distanceProvider = new ConstantSpeedDistanceProvider(10_000, 1000);
  }

  @Test
  @DisplayName("ValidationInput rejects negative vehicle initial SoC")
  void validationInputRejectsNegativeInitialSoc() {
    assertThatThrownBy(
            () -> ValidationInput.builder().tenantId(tenant).addVehicle(validVehicle, hubLoc, -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("initialSocWh");
  }

  @Test
  @DisplayName("ValidationInput rejects vehicle initial SoC exceeding capacity")
  void validationInputRejectsInitialSocExceedingCapacity() {
    assertThatThrownBy(
            () ->
                ValidationInput.builder()
                    .tenantId(tenant)
                    .addVehicle(validVehicle, hubLoc, 60_001L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("initialSocWh");
  }

  @Test
  @DisplayName("ValidationInput rejects negative idle power")
  void validationInputRejectsNegativeIdlePower() {
    assertThatThrownBy(() -> ValidationInput.builder().tenantId(tenant).idlePowerW(-10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("idlePowerW must be non-negative");
  }

  @Test
  @DisplayName("ValidationInput rejects charger referencing unknown hub")
  void validationInputRejectsChargerReferencingUnknownHub() {
    Charger orphanCharger =
        new Charger(
            tenant,
            ChargerId.of("CHG-ORPHAN"),
            HubId.of("HUB-NONEXISTENT"),
            50,
            ChargerStatus.OPERATIONAL,
            List.of());
    assertThatThrownBy(
            () ->
                ValidationInput.builder()
                    .tenantId(tenant)
                    .distanceProvider(distanceProvider)
                    .consumptionModel(new ConstantConsumptionModel())
                    .addCharger(orphanCharger)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("references unregistered hub");
  }

  @Test
  @DisplayName("ValidationInput rejects overlapping UTC tariff windows for same hub")
  void validationInputRejectsOverlappingUtcTariffWindows() {
    UtcTariffWindow w1 = new UtcTariffWindow(hub.id(), t0, t0.plusSeconds(3600), 100_000L);
    UtcTariffWindow w2 =
        new UtcTariffWindow(hub.id(), t0.plusSeconds(1800), t0.plusSeconds(5400), 200_000L);

    assertThatThrownBy(
            () ->
                ValidationInput.builder()
                    .tenantId(tenant)
                    .addHub(hub)
                    .addCharger(charger)
                    .addUtcTariff(w1)
                    .addUtcTariff(w2)
                    .distanceProvider(distanceProvider)
                    .consumptionModel(new ConstantConsumptionModel())
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Overlapping UTC tariff windows");
  }

  @Test
  @DisplayName(
      "Candidate model: Inverted charging session (start >= end) instantiates and fails validation")
  void candidateInvertedChargingSessionNeverThrowsAndFailsValidation() {
    ChargingSession invertedSession =
        new ChargingSession(
            "S-INVERTED",
            validVehicle.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(3600),
            t0.plusSeconds(1800),
            20_000L,
            40_000L,
            500_000L);

    VehicleSchedule sched =
        new VehicleSchedule(validVehicle, hubLoc, 50_000L, t0, List.of(invertedSession));
    FleetPlan candidate =
        new FleetPlan(
            "P-INVERTED",
            tenant.value(),
            t0,
            t0.plusSeconds(7200),
            List.of(sched),
            List.of(),
            1L,
            "hash");

    ValidationInput input =
        ValidationInput.builder()
            .tenantId(tenant)
            .horizon(t0, t0.plusSeconds(7200))
            .addHub(hub)
            .addCharger(charger)
            .addVehicle(validVehicle, hubLoc, 50_000L)
            .distanceProvider(distanceProvider)
            .consumptionModel(new ConstantConsumptionModel())
            .build();

    ValidationReport report = new PlanValidator().validate(input, candidate);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H6_CHARGING_CURVE)).isNotEmpty();
  }

  @Test
  @DisplayName("Candidate model: Negative claimed arrival SoC instantiates and fails validation")
  void candidateNegativeClaimedArrivalSocNeverThrowsAndFailsValidation() {
    TripAssignment assignWithNegativeArrival =
        new TripAssignment(
            validTrip,
            validVehicle.id(),
            validTrip.startTs(),
            validTrip.plannedDropoffTs(),
            10_000,
            1000,
            1500L,
            -500L,
            40_000L);

    VehicleSchedule sched =
        new VehicleSchedule(validVehicle, hubLoc, 50_000L, t0, List.of(assignWithNegativeArrival));
    FleetPlan candidate =
        new FleetPlan(
            "P-NEG-SOC",
            tenant.value(),
            t0,
            t0.plusSeconds(7200),
            List.of(sched),
            List.of(),
            1L,
            "hash");

    ValidationInput input =
        ValidationInput.builder()
            .tenantId(tenant)
            .horizon(t0, t0.plusSeconds(7200))
            .addHub(hub)
            .addCharger(charger)
            .addTrip(validTrip)
            .addVehicle(validVehicle, hubLoc, 50_000L)
            .distanceProvider(distanceProvider)
            .consumptionModel(new ConstantConsumptionModel())
            .build();

    ValidationReport report = new PlanValidator().validate(input, candidate);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.CLAIMED_SOC_MISMATCH)).isNotEmpty();
  }
}
