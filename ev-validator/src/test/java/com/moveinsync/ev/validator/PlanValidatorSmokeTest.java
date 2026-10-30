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

class PlanValidatorSmokeTest {

  private TenantId tenant;
  private Location hubLoc;
  private Location pickupLoc;
  private Location dropoffLoc;
  private Hub hub;
  private Charger charger;
  private Vehicle vehicle;
  private DistanceProvider distanceProvider;
  private PlanValidator validator;

  @BeforeEach
  void setUp() {
    tenant = TenantId.of("tenant-1");
    hubLoc = Location.of("LOC-HUB-1", "Central Hub");
    pickupLoc = Location.of("LOC-PICKUP-1", "Office Pickup");
    dropoffLoc = Location.of("LOC-DROPOFF-1", "Tech Park Dropoff");

    hub = Hub.of("tenant-1", "HUB-1", "Central Hub", hubLoc, ZoneId.of("Asia/Kolkata"), 1);
    charger = Charger.of("tenant-1", "CHG-1", "HUB-1", 50);

    vehicle = new Vehicle(tenant, VehicleId.of("CAB-01"), 60_000L, 150L, 50);

    Map<MatrixDistanceProvider.LocationPair, DistanceDuration> matrix =
        Map.of(
            new MatrixDistanceProvider.LocationPair(hubLoc.id(), pickupLoc.id()),
            DistanceDuration.of(5_000, 600),
            new MatrixDistanceProvider.LocationPair(pickupLoc.id(), dropoffLoc.id()),
            DistanceDuration.of(10_000, 1200),
            new MatrixDistanceProvider.LocationPair(dropoffLoc.id(), hubLoc.id()),
            DistanceDuration.of(5_000, 600));

    distanceProvider =
        new MatrixDistanceProvider(matrix, new ConstantSpeedDistanceProvider(5_000, 10));

    validator = new PlanValidator();
  }

  private ValidationInputBuilder baseInputBuilder(FleetPlan plan) {
    return ValidationInput.builder()
        .tenantId(tenant)
        .horizon(plan.horizonStart(), plan.horizonEnd())
        .consumptionModel(new ConstantConsumptionModel())
        .distanceProvider(distanceProvider)
        .chargingCurve(ChargingCurve.defaultCurve())
        .batteryFloorPercentage(15)
        .enforceReserveToHub(true)
        .addHub(hub)
        .addCharger(charger);
  }

  @Test
  @DisplayName("Valid plan satisfies all invariants H1-H6 and produces 0 violations")
  void validPlanValidation() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);
    Instant tDropoff = tPickup.plusSeconds(1200);
    Instant tLatestFinish = tPickup.plusSeconds(1800);

    Trip trip =
        new Trip(
            tenant,
            TripId.of("TRIP-1"),
            tPickup,
            pickupLoc,
            dropoffLoc,
            10_000,
            1200,
            tLatestFinish,
            "EMP-100");

    TripAssignment assign =
        new TripAssignment(
            trip, vehicle.id(), tPickup, tDropoff, 5_000, 600, 750L, 49_250L, 47_750L);

    Instant tChargeStart = tDropoff.plusSeconds(600);
    Instant tChargeEnd = tChargeStart.plusSeconds(1080);

    ChargingSession session =
        new ChargingSession(
            "SESS-1",
            vehicle.id(),
            hub.id(),
            charger.id(),
            tChargeStart,
            tChargeEnd,
            47_000L,
            55_000L,
            800_000L);

    VehicleSchedule schedule =
        new VehicleSchedule(vehicle, hubLoc, 50_000L, t0, List.of(assign, session));

    FleetPlan plan =
        new FleetPlan(
            "PLAN-01",
            tenant.value(),
            t0,
            tChargeEnd.plusSeconds(3600),
            List.of(schedule),
            List.of(),
            12345L,
            "hash-test");

    ValidationInput input =
        baseInputBuilder(plan).addVehicle(vehicle, hubLoc, 50_000L).addTrip(trip).build();

    ValidationReport report = validator.validate(input, plan);

    assertThat(report.isValid()).isTrue();
    assertThat(report.violationCount()).isEqualTo(0);
    assertThat(report.totalTripsChecked()).isEqualTo(1);
    assertThat(report.totalSessionsChecked()).isEqualTo(1);
    assertThat(report.totalVehiclesChecked()).isEqualTo(1);
    report.assertValid();
  }

  @Test
  @DisplayName("H1: Violation caught when vehicle SoC drops below 15% floor during trip")
  void batteryFloorViolationOnTrip() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);
    Instant tDropoff = tPickup.plusSeconds(1200);

    Trip trip =
        new Trip(
            tenant,
            TripId.of("TRIP-LOW-BATTERY"),
            tPickup,
            pickupLoc,
            dropoffLoc,
            10_000,
            1200,
            tPickup.plusSeconds(1800),
            "EMP-101");

    TripAssignment assign =
        new TripAssignment(trip, vehicle.id(), tPickup, tDropoff, 5_000, 600, 750L, 9_250L, 7_750L);

    VehicleSchedule schedule = new VehicleSchedule(vehicle, hubLoc, 10_000L, t0, List.of(assign));

    FleetPlan plan =
        new FleetPlan(
            "PLAN-02",
            tenant.value(),
            t0,
            t0.plusSeconds(7200),
            List.of(schedule),
            List.of(),
            12345L,
            "hash-test");

    ValidationInput input =
        baseInputBuilder(plan).addVehicle(vehicle, hubLoc, 10_000L).addTrip(trip).build();

    ValidationReport report = validator.validate(input, plan);

    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H1_BATTERY_FLOOR)).isNotEmpty();
    assertThatThrownBy(report::assertValid).isInstanceOf(ValidationException.class);
  }
}
