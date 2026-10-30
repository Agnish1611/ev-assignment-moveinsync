package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * Coverage tests for sub-engines: TariffCostValidator, AssignmentValidator transit, and
 * BatteryFloorValidator.
 */
class ValidatorSubEngineCoverageTest {

  @Test
  @DisplayName("TariffCostValidator argument error paths and local window conversion")
  void tariffCostValidatorErrorPaths() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    HubId hubId = HubId.of("HUB-1");
    UtcTariffWindow window = new UtcTariffWindow(hubId, t0, t0.plusSeconds(3600), 100_000L);

    assertThatThrownBy(
            () ->
                TariffCostValidator.calculateCostMicros(
                    t0.plusSeconds(100), t0, 5000L, List.of(window)))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(
            TariffCostValidator.calculateCostMicros(t0, t0.plusSeconds(100), 0L, List.of(window)))
        .isZero();
    assertThat(
            TariffCostValidator.calculateCostMicros(t0, t0.plusSeconds(100), -50L, List.of(window)))
        .isZero();

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
            .batteryFloorPercentage(15)
            .enforceReserveToHub(true)
            .build();

    BatteryFloorValidator batVal = new BatteryFloorValidator(ctx);
    VehicleSchedule sched = new VehicleSchedule(cab, hubLoc, 8_000L, Instant.EPOCH, List.of());

    List<PlanViolation> violations = batVal.validate(sched);
    assertThat(violations).anyMatch(v -> v.ruleId() == RuleId.H1_BATTERY_FLOOR);
  }
}
