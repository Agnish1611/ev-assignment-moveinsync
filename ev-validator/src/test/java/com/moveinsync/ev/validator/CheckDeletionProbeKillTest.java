package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

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

/** Kill tests for validator check deletions identified during the independent probe. */
class CheckDeletionProbeKillTest {

  private TenantId tenant;
  private Location hubLoc;
  private Location pickupLoc;
  private Location dropoffLoc;
  private Hub hub;
  private Charger charger1;
  private Charger charger2;
  private Vehicle vehicle1;
  private Vehicle vehicle2;
  private DistanceProvider distanceProvider;
  private PlanValidator validator;

  @BeforeEach
  void setUp() {
    tenant = TenantId.of("tenant-1");
    hubLoc = Location.of("LOC-HUB-1", "Central Hub");
    pickupLoc = Location.of("LOC-PICKUP-1", "Office Pickup");
    dropoffLoc = Location.of("LOC-DROPOFF-1", "Tech Park Dropoff");

    // Hub with 2 physical chargers
    hub = Hub.of("tenant-1", "HUB-1", "Central Hub", hubLoc, ZoneId.of("Asia/Kolkata"), 2);
    charger1 = Charger.of("tenant-1", "CHG-1", "HUB-1", 50);
    charger2 = Charger.of("tenant-1", "CHG-2", "HUB-1", 50);

    vehicle1 = new Vehicle(tenant, VehicleId.of("CAB-01"), 60_000L, 150L, 50);
    vehicle2 = new Vehicle(tenant, VehicleId.of("CAB-02"), 60_000L, 150L, 50);

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
        .addHub(hub)
        .addCharger(charger1)
        .addCharger(charger2)
        .distanceProvider(distanceProvider)
        .consumptionModel(new ConstantConsumptionModel())
        .chargingCurve(ChargingCurve.defaultCurve())
        .batteryFloorPercentage(15)
        .enforceReserveToHub(true);
  }

  @Test
  @DisplayName(
      "Kill probe: plug conflict caught when 2 cabs use same charger even if hub has spare capacity")
  void plugConflictDetectedEvenWhenHubHasSpareCapacity() {
    Instant tStart = Instant.parse("2026-10-30T08:00:00Z");
    Instant tEnd = tStart.plusSeconds(1800);

    // Both vehicles book charger1 simultaneously at the 2-charger hub
    ChargingSession sess1 =
        new ChargingSession(
            "S-1",
            vehicle1.id(),
            hub.id(),
            charger1.id(),
            tStart,
            tEnd,
            20_000L,
            40_000L,
            500_000L);
    ChargingSession sess2 =
        new ChargingSession(
            "S-2",
            vehicle2.id(),
            hub.id(),
            charger1.id(),
            tStart,
            tEnd,
            20_000L,
            40_000L,
            500_000L);

    VehicleSchedule s1 = new VehicleSchedule(vehicle1, hubLoc, 20_000L, tStart, List.of(sess1));
    VehicleSchedule s2 = new VehicleSchedule(vehicle2, hubLoc, 20_000L, tStart, List.of(sess2));

    FleetPlan plan =
        new FleetPlan(
            "PLAN-PLUG-CONFLICT",
            tenant.value(),
            tStart,
            tEnd.plusSeconds(3600),
            List.of(s1, s2),
            List.of(),
            1L,
            "h");

    ValidationInput input =
        baseInputBuilder(plan)
            .addVehicle(vehicle1, hubLoc, 20_000L)
            .addVehicle(vehicle2, hubLoc, 20_000L)
            .build();

    ValidationReport report = validator.validate(input, plan);

    assertThat(report.isValid()).isFalse();
    // Hub capacity is NOT exceeded (2 concurrent sessions <= 2 chargers)
    assertThat(report.violationsFor(RuleId.H2_CHARGER_CAPACITY)).isEmpty();
    // Plug double booking IS detected
    assertThat(report.violationsFor(RuleId.CHARGER_DOUBLE_BOOKED)).isNotEmpty();
    assertThat(report.violationsFor(RuleId.CHARGER_DOUBLE_BOOKED).get(0).message())
        .contains("Plug conflict on charger CHG-1");
  }

  @Test
  @DisplayName(
      "Kill probe: trip dropoff scheduled earlier than physically possible duration is rejected")
  void tripDropoffBeforeEstimatedTripDurationRejected() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);
    // Estimated duration is 1200 seconds, but candidate schedules dropoff only 300 seconds later
    Instant impossibleDropoff = tPickup.plusSeconds(300);
    Instant tLatestFinish = tPickup.plusSeconds(2400);

    Trip trip =
        new Trip(
            tenant,
            TripId.of("TRIP-FAST"),
            tPickup,
            pickupLoc,
            dropoffLoc,
            10_000,
            1200,
            tLatestFinish,
            "EMP-1");

    TripAssignment assign =
        new TripAssignment(
            trip, vehicle1.id(), tPickup, impossibleDropoff, 5_000, 600, 750L, 49_250L, 47_750L);

    VehicleSchedule sched = new VehicleSchedule(vehicle1, hubLoc, 50_000L, t0, List.of(assign));
    FleetPlan plan =
        new FleetPlan(
            "PLAN-IMPOSSIBLE-SPEED",
            tenant.value(),
            t0,
            tLatestFinish.plusSeconds(3600),
            List.of(sched),
            List.of(),
            1L,
            "h");

    ValidationInput input =
        baseInputBuilder(plan).addVehicle(vehicle1, hubLoc, 50_000L).addTrip(trip).build();

    ValidationReport report = validator.validate(input, plan);

    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.H3_TRIP_TIMING)).isNotEmpty();
    assertThat(
            report.violationsFor(RuleId.H3_TRIP_TIMING).stream()
                .anyMatch(
                    v ->
                        v.message()
                            .contains("is earlier than minimum physical travel time allows")))
        .isTrue();
  }

  @Test
  @DisplayName("Kill probe: charging session starting below floor is specifically reported")
  void sessionStartBelowBatteryFloorExplicitlyReported() {
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);
    Instant tDropoff = tPickup.plusSeconds(1200);
    Instant tLatestFinish = tPickup.plusSeconds(1800);

    Trip trip =
        new Trip(
            tenant,
            TripId.of("TRIP-DRAIN"),
            tPickup,
            pickupLoc,
            dropoffLoc,
            10_000,
            1200,
            tLatestFinish,
            "EMP-1");

    // Start with 10,000 Wh. Floor is 9,000 Wh (15% of 60k).
    // Deadhead 5 km @ 150 Wh/km = 750 Wh -> arrival at pickup = 9,250 Wh.
    // Trip 10 km @ 150 Wh/km = 1,500 Wh -> dropoff = 7,750 Wh.
    // Return to hub 5 km @ 150 Wh/km = 750 Wh -> arrival at hub = 7,000 Wh.
    // Session starts at arrival at hub (7,000 Wh), which is below 9,000 Wh floor.
    TripAssignment assign =
        new TripAssignment(
            trip, vehicle1.id(), tPickup, tDropoff, 5_000, 600, 750L, 9_250L, 7_750L);

    Instant tChargeStart = tDropoff.plusSeconds(600);
    Instant tChargeEnd = tChargeStart.plusSeconds(3600);
    ChargingSession session =
        new ChargingSession(
            "S-DRAINED",
            vehicle1.id(),
            hub.id(),
            charger1.id(),
            tChargeStart,
            tChargeEnd,
            7_000L,
            50_000L,
            500_000L);

    VehicleSchedule sched =
        new VehicleSchedule(vehicle1, hubLoc, 10_000L, t0, List.of(assign, session));

    FleetPlan plan =
        new FleetPlan(
            "PLAN-DRAIN",
            tenant.value(),
            t0,
            tChargeEnd.plusSeconds(3600),
            List.of(sched),
            List.of(),
            1L,
            "h");

    ValidationInput input =
        baseInputBuilder(plan).addVehicle(vehicle1, hubLoc, 10_000L).addTrip(trip).build();

    ValidationReport report = validator.validate(input, plan);

    assertThat(report.isValid()).isFalse();
    assertThat(
            report.violationsFor(RuleId.H1_BATTERY_FLOOR).stream()
                .anyMatch(v -> v.message().contains("starts charging below floor")))
        .isTrue();
  }
}
