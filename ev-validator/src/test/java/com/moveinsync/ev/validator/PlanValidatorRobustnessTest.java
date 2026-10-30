package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.moveinsync.ev.domain.charging.ChargingCurve;
import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.distance.MatrixDistanceProvider;
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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlanValidatorRobustnessTest {

  private TenantId tenantId;
  private Location hubLoc;
  private Location pickupLoc;
  private Location dropoffLoc;
  private Location missingLoc;
  private Hub hub;
  private Charger charger;
  private Vehicle vehicle;
  private Instant t0;
  private ValidationInput input;
  private PlanValidator validator;

  @BeforeEach
  void setUp() {
    tenantId = TenantId.of("tenant-robustness");
    hubLoc = Location.of("LOC-HUB", "Hub North");
    pickupLoc = Location.of("LOC-PICKUP", "Pickup Alpha");
    dropoffLoc = Location.of("LOC-DROPOFF", "Dropoff Beta");
    missingLoc = Location.of("LOC-UNMAPPED", "Unmapped Route Point");

    hub = Hub.of(tenantId.value(), "HUB-1", "Hub North", hubLoc, ZoneId.of("UTC"), 1);
    charger = Charger.of(tenantId.value(), "CHG-1", "HUB-1", 50);
    vehicle = new Vehicle(tenantId, VehicleId.of("CAB-01"), 60_000L, 150L, 50);

    t0 = Instant.parse("2026-10-30T08:00:00Z");

    Map<MatrixDistanceProvider.LocationPair, DistanceDuration> matrix =
        Map.of(
            new MatrixDistanceProvider.LocationPair(hubLoc.id(), pickupLoc.id()),
            DistanceDuration.of(5_000, 600),
            new MatrixDistanceProvider.LocationPair(pickupLoc.id(), dropoffLoc.id()),
            DistanceDuration.of(10_000, 1200),
            new MatrixDistanceProvider.LocationPair(dropoffLoc.id(), hubLoc.id()),
            DistanceDuration.of(5_000, 600));

    input =
        ValidationInput.builder()
            .tenantId(tenantId)
            .horizon(t0, t0.plusSeconds(86400))
            .addHub(hub)
            .addCharger(charger)
            .addVehicle(vehicle, hubLoc, 50_000L)
            .distanceProvider(new MatrixDistanceProvider(matrix))
            .consumptionModel(new ConstantConsumptionModel())
            .chargingCurve(ChargingCurve.defaultCurve())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(true)
            .build();

    validator = new PlanValidator();
  }

  @Test
  @DisplayName(
      "F-05: Missing distance corridor produces ROUTE_UNAVAILABLE violation without throwing")
  void missingDistanceCorridorProducesRouteUnavailable() {
    Trip unmappedTrip =
        new Trip(
            tenantId,
            TripId.of("TRIP-UNMAPPED"),
            t0.plusSeconds(600),
            missingLoc,
            dropoffLoc,
            5_000,
            600,
            t0.plusSeconds(2400),
            "EMP-UNMAPPED");

    TripAssignment assign =
        new TripAssignment(
            unmappedTrip,
            vehicle.id(),
            t0.plusSeconds(600),
            t0.plusSeconds(1200),
            5000,
            600,
            750L,
            49250L,
            48500L);
    VehicleSchedule sched = new VehicleSchedule(vehicle, hubLoc, 50_000L, t0, List.of(assign));

    ValidationInput customInput =
        ValidationInput.builder()
            .tenantId(tenantId)
            .horizon(t0, t0.plusSeconds(86400))
            .addHub(hub)
            .addCharger(charger)
            .addVehicle(vehicle, hubLoc, 50_000L)
            .addTrip(unmappedTrip)
            .distanceProvider(input.distanceProvider())
            .consumptionModel(new ConstantConsumptionModel())
            .build();

    FleetPlan candidate =
        new FleetPlan(
            "P-MISSING-ROUTE",
            tenantId,
            t0,
            t0.plusSeconds(3600),
            List.of(sched),
            List.of(),
            1L,
            "hash");

    assertThatCode(
            () -> {
              ValidationReport report = validator.validate(customInput, candidate);
              assertThat(report.isValid()).isFalse();
              assertThat(report.violationsFor(RuleId.ROUTE_UNAVAILABLE)).isNotEmpty();
            })
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName(
      "F-04: Charging session with target SoC exceeding capacity produces SOC_OUT_OF_RANGE without throwing")
  void targetSocExceedingCapacityProducesSocOutOfRange() {
    // 70,000 Wh > 60,000 Wh battery capacity
    ChargingSession session =
        new ChargingSession(
            "SESS-OVERFLOW",
            vehicle.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(100),
            t0.plusSeconds(1000),
            40_000L,
            70_000L,
            500_000L);
    VehicleSchedule sched = new VehicleSchedule(vehicle, hubLoc, 50_000L, t0, List.of(session));

    FleetPlan candidate =
        new FleetPlan(
            "P-OVERFLOW-SOC",
            tenantId,
            t0,
            t0.plusSeconds(3600),
            List.of(sched),
            List.of(),
            1L,
            "hash");

    assertThatCode(
            () -> {
              ValidationReport report = validator.validate(input, candidate);
              assertThat(report.isValid()).isFalse();
              assertThat(report.violationsFor(RuleId.SOC_OUT_OF_RANGE)).isNotEmpty();
            })
        .doesNotThrowAnyException();
  }
}
