package com.moveinsync.ev.validator;

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

/** Shared test fixture for single-fault mutation testing across invariants H1 through H6. */
abstract class ValidatorMutationTestFixture {

  protected Location hub1Loc;
  protected Location pickupLoc;
  protected Location dropoffLoc;
  protected Hub hub1;
  protected Charger charger1;
  protected Vehicle cab1;
  protected Vehicle cab2;
  protected ValidationContext context;
  protected DistanceProvider distanceProvider;
  protected PlanValidator validator;

  protected Trip baselineTrip;
  protected FleetPlan baselineValidPlan;

  @BeforeEach
  void setUp() {
    hub1Loc = Location.of("LOC-HUB-1", "North Hub");
    pickupLoc = Location.of("LOC-PICKUP", "Pickup A");
    dropoffLoc = Location.of("LOC-DROPOFF", "Dropoff B");

    hub1 = Hub.of("tenant-1", "HUB-1", "North Hub", hub1Loc, ZoneId.of("Asia/Kolkata"), 1);
    charger1 = Charger.of("tenant-1", "CHG-1", "HUB-1", 50);

    cab1 = new Vehicle(TenantId.of("tenant-1"), VehicleId.of("CAB-01"), 60_000L, 150L, 50);
    cab2 = new Vehicle(TenantId.of("tenant-1"), VehicleId.of("CAB-02"), 60_000L, 150L, 50);

    Map<MatrixDistanceProvider.LocationPair, DistanceDuration> matrix =
        Map.of(
            new MatrixDistanceProvider.LocationPair(hub1Loc.id(), pickupLoc.id()),
            DistanceDuration.of(5_000, 600),
            new MatrixDistanceProvider.LocationPair(pickupLoc.id(), dropoffLoc.id()),
            DistanceDuration.of(10_000, 1200),
            new MatrixDistanceProvider.LocationPair(dropoffLoc.id(), hub1Loc.id()),
            DistanceDuration.of(5_000, 600));

    distanceProvider =
        new MatrixDistanceProvider(matrix, new ConstantSpeedDistanceProvider(5_000, 10));

    context =
        ValidationContext.builder()
            .addHub(hub1)
            .addCharger(charger1)
            .consumptionModel(new ConstantConsumptionModel())
            .distanceProvider(distanceProvider)
            .chargingCurve(ChargingCurve.defaultCurve())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(true)
            .build();

    validator = new PlanValidator();

    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Instant tPickup = t0.plusSeconds(600);
    Instant tDropoff = tPickup.plusSeconds(1200);
    Instant tLatestFinish = tPickup.plusSeconds(1800);

    baselineTrip =
        new Trip(
            TenantId.of("tenant-1"),
            TripId.of("TRIP-BASE"),
            tPickup,
            pickupLoc,
            dropoffLoc,
            10_000,
            1200,
            tLatestFinish,
            "EMP-MUT-01");

    TripAssignment assign =
        new TripAssignment(
            baselineTrip, cab1.id(), tPickup, tDropoff, 5_000, 600, 750L, 49_250L, 47_750L);

    Instant tChargeStart = tDropoff.plusSeconds(600);
    Instant tChargeEnd = tChargeStart.plusSeconds(1080);

    ChargingSession session =
        new ChargingSession(
            "SESS-BASE",
            cab1.id(),
            hub1.id(),
            charger1.id(),
            tChargeStart,
            tChargeEnd,
            47_000L,
            55_000L,
            750_000L);

    VehicleSchedule schedule1 =
        new VehicleSchedule(cab1, hub1Loc, 50_000L, t0, List.of(assign, session));

    baselineValidPlan =
        new FleetPlan(
            "PLAN-BASELINE",
            "tenant-1",
            t0,
            tChargeEnd.plusSeconds(3600),
            List.of(schedule1),
            List.of(),
            999L,
            "hash-base");
  }

  protected ValidationInput baseInput(FleetPlan plan) {
    ValidationInputBuilder b =
        ValidationInput.builder()
            .tenantId(TenantId.of("tenant-1"))
            .horizon(plan.horizonStart(), plan.horizonEnd())
            .consumptionModel(context.consumptionModel())
            .distanceProvider(context.distanceProvider())
            .chargingCurve(context.chargingCurve())
            .batteryFloorPercentage(context.batteryFloorPercentage())
            .enforceReserveToHub(context.enforceReserveToHub())
            .addHub(hub1)
            .addCharger(charger1);
    for (VehicleSchedule vs : plan.vehicleSchedules()) {
      b.addVehicle(vs.vehicle(), vs.initialLocation(), vs.initialSocWh());
    }
    b.addTrip(baselineTrip);
    return b.build();
  }
}
