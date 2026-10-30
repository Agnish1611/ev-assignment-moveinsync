package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.DomainMarker;
import com.moveinsync.ev.domain.charging.ChargingCurve;
import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.tariff.TariffWindow;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Targeted boundary and coverage tests for ev-validator to meet the strict >= 90% line and >= 80%
 * branch gates.
 */
class ValidatorCoverageTest {

  @Test
  @DisplayName("ValidatorMarker constructor and validation guards")
  void validatorMarkerCoverage() {
    DomainMarker dm = new DomainMarker(1);
    ValidatorMarker vm = new ValidatorMarker(dm);
    assertThat(vm.domainMarker()).isSameAs(dm);

    assertThatThrownBy(() -> new ValidatorMarker(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("domainMarker must not be null");
  }

  @Test
  @DisplayName("ValidationReport success, assertValid, and toDetailedSummary")
  void validationReportCoverage() {
    ValidationReport validReport = ValidationReport.success(5, 10, 3);
    assertThat(validReport.isValid()).isTrue();
    assertThat(validReport.violationCount()).isZero();
    assertThat(validReport.totalVehiclesChecked()).isEqualTo(5);
    assertThat(validReport.totalTripsChecked()).isEqualTo(10);
    assertThat(validReport.totalSessionsChecked()).isEqualTo(3);
    validReport.assertValid(); // Does not throw

    String validSummary = validReport.toDetailedSummary();
    assertThat(validSummary).contains("PASSED (All invariants H1-H6 satisfied)");
    assertThat(validSummary).contains("Vehicles evaluated: 5");

    PlanViolation v1 = PlanViolation.ofGeneral(RuleId.H1_BATTERY_FLOOR, "Floor breach test");
    ValidationReport failReport = new ValidationReport(List.of(v1), 1, 1, 1);
    assertThat(failReport.isValid()).isFalse();
    assertThat(failReport.violationCount()).isEqualTo(1);

    assertThatThrownBy(failReport::assertValid)
        .isInstanceOf(ValidationException.class)
        .satisfies(
            ex -> {
              ValidationException ve = (ValidationException) ex;
              assertThat(ve.getReport()).isSameAs(failReport);
            });

    String failSummary = failReport.toDetailedSummary();
    assertThat(failSummary).contains("FAILED with 1 violation(s)");
    assertThat(failSummary).contains("[H1_BATTERY_FLOOR] Floor breach test");
  }

  @Test
  @DisplayName("ValidationContext and ValidationInput boundary validations and getters")
  void validationInputAndContextBoundaries() {
    Location loc = Location.of("L1", "Loc 1");
    Hub hub = Hub.of("t1", "H1", "Hub 1", loc, ZoneId.of("Asia/Kolkata"), 1);
    Charger chg = Charger.of("t1", "C1", "H1", 50);
    Vehicle cab = new Vehicle(TenantId.of("t1"), VehicleId.of("CAB-01"), 60_000L, 150L, 50);
    Trip trip =
        new Trip(
            TenantId.of("t1"),
            TripId.of("T1"),
            Instant.EPOCH.plusSeconds(100),
            loc,
            loc,
            5000,
            600,
            Instant.EPOCH.plusSeconds(1000),
            "E1");
    DistanceProvider dp = new ConstantSpeedDistanceProvider(5000, 10);
    ConstantConsumptionModel cm = new ConstantConsumptionModel();
    ChargingCurve cc = ChargingCurve.defaultCurve();

    // ValidationContext invalid battery floors
    assertThatThrownBy(
            () ->
                new ValidationContext(
                    Map.of(hub.id(), hub), Map.of(chg.id(), chg), cm, dp, cc, 0, true, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ValidationContext(
                    Map.of(hub.id(), hub), Map.of(chg.id(), chg), cm, dp, cc, 100, true, false))
        .isInstanceOf(IllegalArgumentException.class);

    // ValidationInput horizon start before end guard
    assertThatThrownBy(
            () ->
                new ValidationInput(
                    TenantId.of("t1"),
                    Instant.EPOCH.plusSeconds(100),
                    Instant.EPOCH.plusSeconds(50),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    List.of(),
                    List.of(),
                    dp,
                    cm,
                    cc,
                    15,
                    true,
                    0))
        .isInstanceOf(IllegalArgumentException.class);

    // ValidationInput invalid floor
    assertThatThrownBy(
            () ->
                new ValidationInput(
                    TenantId.of("t1"),
                    Instant.EPOCH,
                    Instant.EPOCH.plusSeconds(100),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    List.of(),
                    List.of(),
                    dp,
                    cm,
                    cc,
                    -5,
                    true,
                    0))
        .isInstanceOf(IllegalArgumentException.class);

    // ValidationInput builder with all fields
    ValidationInput input =
        ValidationInput.builder()
            .tenantId(TenantId.of("t1"))
            .horizon(Instant.EPOCH, Instant.EPOCH.plusSeconds(7200))
            .addVehicle(cab, loc, 50_000L)
            .addHub(hub)
            .addCharger(chg)
            .addTrip(trip)
            .addTariff(
                new TariffWindow(hub.id(), LocalTime.of(0, 0), LocalTime.of(23, 59), 100_000L))
            .addUtcTariff(
                new UtcTariffWindow(
                    hub.id(), Instant.EPOCH, Instant.EPOCH.plusSeconds(7200), 100_000L))
            .distanceProvider(dp)
            .consumptionModel(cm)
            .chargingCurve(cc)
            .batteryFloorPercentage(15)
            .enforceReserveToHub(true)
            .idlePowerW(25)
            .build();

    assertThat(input.tenantId().value()).isEqualTo("t1");
    assertThat(input.vehicles()).containsKey(cab.id());
    assertThat(input.initialLocations()).containsKey(cab.id());
    assertThat(input.initialSocsWh()).containsKey(cab.id());
    assertThat(input.trips()).containsKey(trip.id());
    assertThat(input.hubs()).containsKey(hub.id());
    assertThat(input.chargers()).containsKey(chg.id());
    assertThat(input.tariffs()).hasSize(1);
    assertThat(input.utcTariffs()).hasSize(1);
    assertThat(input.distanceProvider()).isSameAs(dp);
    assertThat(input.consumptionModel()).isSameAs(cm);
    assertThat(input.chargingCurve()).isSameAs(cc);
    assertThat(input.batteryFloorPercentage()).isEqualTo(15);
    assertThat(input.enforceReserveToHub()).isTrue();
    assertThat(input.idlePowerW()).isEqualTo(25);
    assertThat(input.findVehicle(cab.id())).isPresent();
    assertThat(input.findTrip(trip.id())).isPresent();
    assertThat(input.findHub(hub.id())).isPresent();
    assertThat(input.findCharger(chg.id())).isPresent();
  }
}
