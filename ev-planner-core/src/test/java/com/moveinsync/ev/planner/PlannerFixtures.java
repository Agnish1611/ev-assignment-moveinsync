package com.moveinsync.ev.planner;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.distance.MatrixDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.ChargerStatus;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.OutageWindow;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.location.LocationId;
import com.moveinsync.ev.domain.tariff.TariffSchedule;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** Small deterministic shift used by planner tests. */
public final class PlannerFixtures {

  public static final TenantId TENANT = TenantId.of("tenant-a");
  public static final Instant START = Instant.parse("2026-10-30T08:00:00Z");
  public static final Instant END = START.plusSeconds(28_800);

  private PlannerFixtures() {}

  public static PlannerInput sample() {
    return builder().build();
  }

  public static PlannerInput.Builder builder() {
    Location depot = Location.of("LOC-HUB", "Hub");
    Location pickup = Location.of("LOC-P", "Pickup");
    Location dropoff = Location.of("LOC-D", "Drop");
    Hub hub = Hub.of(TENANT.value(), "HUB-1", "Hub", depot, ZoneId.of("UTC"), 1);
    Charger charger = Charger.of(TENANT.value(), "CHG-1", "HUB-1", 50);
    CabShift later =
        new CabShift(
            new Vehicle(TENANT, VehicleId.of("CAB-2"), 60_000L, 150L, 50), depot, 40_000L, START);
    CabShift earlier =
        new CabShift(
            new Vehicle(TENANT, VehicleId.of("CAB-1"), 60_000L, 150L, 50), depot, 50_000L, START);
    return PlannerInput.builder()
        .tenantId(TENANT)
        .horizon(START, END)
        .addHub(hub)
        .addCharger(charger)
        .addCab(later)
        .addCab(earlier)
        .addTrip(trip("TRIP-2", START.plusSeconds(3_600)))
        .addTrip(trip("TRIP-1", START.plusSeconds(600), pickup, dropoff))
        .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
        .consumptionModel(new ConstantConsumptionModel());
  }

  public static Trip trip(String id, Instant pickupTime) {
    return trip(id, pickupTime, Location.of("LOC-P", "Pickup"), Location.of("LOC-D", "Drop"));
  }

  public static Trip trip(String id, Instant pickupTime, Location from, Location to) {
    return trip(id, pickupTime, from, to, 10_000);
  }

  public static Trip trip(
      String id, Instant pickupTime, Location from, Location to, int distanceMeters) {
    return new Trip(
        TENANT,
        TripId.of(id),
        pickupTime,
        from,
        to,
        distanceMeters,
        1_200,
        pickupTime.plusSeconds(3_600),
        "EMP-1");
  }

  public static Location depot() {
    return Location.of("LOC-HUB", "Hub");
  }

  /** Horizon, one hub, one charger, and the constant distance and consumption models. */
  public static PlannerInput.Builder shell() {
    Location depot = depot();
    Hub hub = Hub.of(TENANT.value(), "HUB-1", "Hub", depot, ZoneId.of("UTC"), 4);
    Charger charger = Charger.of(TENANT.value(), "CHG-1", "HUB-1", 50);
    return PlannerInput.builder()
        .tenantId(TENANT)
        .horizon(START, END)
        .addHub(hub)
        .addCharger(charger)
        .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
        .consumptionModel(new ConstantConsumptionModel());
  }

  public static CabShift cab(String id, long socWh) {
    return cab(id, socWh, 150L, depot());
  }

  public static CabShift cab(String id, long socWh, long whPerKm, Location location) {
    return new CabShift(
        new Vehicle(TENANT, VehicleId.of(id), 60_000L, whPerKm, 50), location, socWh, START);
  }

  public static PlannerInput withMatrix() {
    MatrixDistanceProvider.LocationPair pair =
        new MatrixDistanceProvider.LocationPair(LocationId.of("LOC-P"), LocationId.of("LOC-D"));
    MatrixDistanceProvider matrix =
        new MatrixDistanceProvider(
            Map.of(pair, DistanceDuration.of(4_000, 400)),
            new ConstantSpeedDistanceProvider(5_000, 10));
    return builder().distanceProvider(matrix).build();
  }

  public static PlannerInput withTariffAndOutage() {
    Location depot = Location.of("LOC-HUB", "Hub");
    Hub hub = Hub.of(TENANT.value(), "HUB-1", "Hub", depot, ZoneId.of("UTC"), 1);
    Charger charger =
        new Charger(
            TENANT,
            ChargerId.of("CHG-1"),
            hub.id(),
            50,
            ChargerStatus.OPERATIONAL,
            List.of(new OutageWindow(START, START.plusSeconds(60), "maintenance")));
    TariffSchedule tariff =
        new TariffSchedule(hub.id(), List.of(new UtcTariffWindow(hub.id(), START, END, 100_000L)));
    return PlannerInput.builder()
        .tenantId(TENANT)
        .horizon(START, END)
        .addHub(hub)
        .addCharger(charger)
        .addCab(
            new CabShift(
                new Vehicle(TENANT, VehicleId.of("CAB-1"), 60_000L, 150L, 50),
                depot,
                50_000L,
                START))
        .addTrip(trip("TRIP-1", START.plusSeconds(600)))
        .addTariff(tariff)
        .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
        .consumptionModel(new ConstantConsumptionModel())
        .build();
  }
}
