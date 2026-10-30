package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.DomainMarker;
import com.moveinsync.ev.domain.charging.ChargingCurve;
import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.distance.MatrixDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
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
    Hub hub = Hub.of("H1", "Hub 1", loc, ZoneId.of("Asia/Kolkata"), 1);
    Charger chg = Charger.of("C1", "H1", 50);
    Vehicle cab = new Vehicle(VehicleId.of("CAB-01"), 60_000L, 150L, 50);
    Trip trip =
        new Trip(
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

  @Test
  @DisplayName("TariffCostValidator argument error paths and local window conversion")
  void tariffCostValidatorErrorPaths() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    HubId hubId = HubId.of("HUB-1");
    UtcTariffWindow window = new UtcTariffWindow(hubId, t0, t0.plusSeconds(3600), 100_000L);

    // Start not before end
    assertThatThrownBy(
            () ->
                TariffCostValidator.calculateCostMicros(
                    t0.plusSeconds(100), t0, 5000L, List.of(window)))
        .isInstanceOf(IllegalArgumentException.class);

    // Energy <= 0 returns 0
    assertThat(
            TariffCostValidator.calculateCostMicros(t0, t0.plusSeconds(100), 0L, List.of(window)))
        .isZero();
    assertThat(
            TariffCostValidator.calculateCostMicros(t0, t0.plusSeconds(100), -50L, List.of(window)))
        .isZero();

    // Local TariffWindow conversion via ValidationInput
    Location loc = Location.of("LOC-1", "Hub 1 Loc");
    Hub hub = Hub.of("HUB-1", "Hub 1", loc, ZoneId.of("Asia/Kolkata"), 1);
    ValidationInput inputWithLocalTariff =
        ValidationInput.builder()
            .tenantId(TenantId.of("tenant-1"))
            .horizon(t0, t0.plusSeconds(7200))
            .addHub(hub)
            .addTariff(new TariffWindow(hubId, LocalTime.of(0, 0), LocalTime.of(23, 59), 100_000L))
            .distanceProvider(new ConstantSpeedDistanceProvider(5000, 10))
            .consumptionModel(new ConstantConsumptionModel())
            .chargingCurve(ChargingCurve.defaultCurve())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(true)
            .build();

    TariffCostValidator validator = new TariffCostValidator(inputWithLocalTariff);
    FleetPlan emptyPlan =
        new FleetPlan(
            "P-EMPTY", "tenant-1", t0, t0.plusSeconds(7200), List.of(), List.of(), 1L, "h");
    assertThat(validator.validate(emptyPlan)).isEmpty();
  }

  @Test
  @DisplayName("AssignmentValidator transit resolution between trip and charging session")
  void assignmentValidatorTransitBetweenTripAndSession() {
    Location hubLoc = Location.of("LOC-HUB", "Hub");
    Location pickupLoc = Location.of("LOC-PICK", "Pick");
    Location dropLoc = Location.of("LOC-DROP", "Drop");

    Hub hub = Hub.of("HUB-1", "Hub", hubLoc, ZoneId.of("Asia/Kolkata"), 1);
    Charger chg = Charger.of("CHG-1", "HUB-1", 50);
    Vehicle cab = new Vehicle(VehicleId.of("CAB-01"), 60_000L, 150L, 50);

    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Trip trip =
        new Trip(
            TripId.of("T1"),
            t0.plusSeconds(600),
            pickupLoc,
            dropLoc,
            10_000,
            1200,
            t0.plusSeconds(3600),
            "E1");
    TripAssignment assign =
        new TripAssignment(
            trip,
            cab.id(),
            trip.startTs(),
            trip.startTs().plusSeconds(1200),
            5000,
            600,
            750L,
            50_000L,
            48_000L);

    // Charging session starting immediately at dropoff, but vehicle is at dropLoc and needs transit
    // to hubLoc
    ChargingSession sess =
        new ChargingSession(
            "S1",
            cab.id(),
            hub.id(),
            chg.id(),
            trip.startTs().plusSeconds(1200),
            trip.startTs().plusSeconds(2400),
            48_000L,
            55_000L,
            100_000L);

    // Matrix with NO entry between dropLoc and hubLoc -> ROUTE_UNAVAILABLE
    Map<MatrixDistanceProvider.LocationPair, DistanceDuration> matrix =
        Map.of(
            new MatrixDistanceProvider.LocationPair(pickupLoc.id(), dropLoc.id()),
            DistanceDuration.of(10_000, 1200));
    DistanceProvider dp = new MatrixDistanceProvider(matrix);

    ValidationContext ctx =
        ValidationContext.builder()
            .addHub(hub)
            .addCharger(chg)
            .distanceProvider(dp)
            .consumptionModel(new ConstantConsumptionModel())
            .chargingCurve(ChargingCurve.defaultCurve())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(true)
            .build();

    AssignmentValidator assignVal = new AssignmentValidator(ctx);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 50_000L, t0, List.of(assign, sess));
    FleetPlan plan =
        new FleetPlan(
            "P-TRANSIT", "tenant-1", t0, t0.plusSeconds(7200), List.of(sched), List.of(), 1L, "h");

    List<PlanViolation> violations = assignVal.validate(plan);
    assertThat(violations).anyMatch(v -> v.ruleId() == RuleId.ROUTE_UNAVAILABLE);
  }

  @Test
  @DisplayName("BatteryFloorValidator initial SoC below floor produces H1_BATTERY_FLOOR")
  void initialSocBelowFloor() {
    Location hubLoc = Location.of("LOC-HUB", "Hub");
    Hub hub = Hub.of("HUB-1", "Hub", hubLoc, ZoneId.of("Asia/Kolkata"), 1);
    Charger chg = Charger.of("CHG-1", "HUB-1", 50);
    Vehicle cab = new Vehicle(VehicleId.of("CAB-01"), 60_000L, 150L, 50);

    ValidationContext ctx =
        ValidationContext.builder()
            .addHub(hub)
            .addCharger(chg)
            .distanceProvider(new ConstantSpeedDistanceProvider(5000, 10))
            .consumptionModel(new ConstantConsumptionModel())
            .chargingCurve(ChargingCurve.defaultCurve())
            .batteryFloorPercentage(15) // floor = 9,000 Wh
            .enforceReserveToHub(true)
            .build();

    BatteryFloorValidator batVal = new BatteryFloorValidator(ctx);
    // Initial SoC 8,000 Wh (< 9,000 Wh floor)
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 8_000L, Instant.EPOCH, List.of());

    List<PlanViolation> violations = batVal.validate(sched);
    assertThat(violations).anyMatch(v -> v.ruleId() == RuleId.H1_BATTERY_FLOOR);
  }
}
