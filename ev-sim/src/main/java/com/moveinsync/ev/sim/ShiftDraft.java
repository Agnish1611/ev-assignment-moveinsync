package com.moveinsync.ev.sim;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.consumption.ConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.tariff.TariffSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.time.ZoneId;

/** Shared pieces of a small named shift. Identifiers stay opaque. */
final class ShiftDraft {

  private static final long BATTERY_WH = 60_000L;
  private static final long WH_PER_KM = 150L;
  private static final int CHARGE_KW = 50;

  private final TenantId tenant;
  private final Instant start;
  private final Location depot;
  private final Location pickup;
  private final Location dropoff;
  private final PlannerInput.Builder builder;
  private final HubId hubId;

  private ShiftDraft(
      TenantId tenant,
      Instant start,
      Location depot,
      Location pickup,
      Location dropoff,
      PlannerInput.Builder builder,
      HubId hubId) {
    this.tenant = tenant;
    this.start = start;
    this.depot = depot;
    this.pickup = pickup;
    this.dropoff = dropoff;
    this.builder = builder;
    this.hubId = hubId;
  }

  static ShiftDraft open(String tenantValue, Instant start, Instant end, int plugs) {
    return open(tenantValue, start, end, ZoneId.of("UTC"), plugs);
  }

  static ShiftDraft open(String tenantValue, Instant start, Instant end, ZoneId zone, int plugs) {
    TenantId tenant = TenantId.of(tenantValue);
    Location depot = Location.of("LOC-HUB", "Hub");
    HubId hubId = HubId.of("HUB-1");
    PlannerInput.Builder builder =
        PlannerInput.builder()
            .tenantId(tenant)
            .horizon(start, end)
            .addHub(Hub.of(tenantValue, "HUB-1", "Hub", depot, zone, plugs))
            .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
            .consumptionModel(new ConstantConsumptionModel());
    for (int charger = 0; charger < plugs; charger++) {
      builder.addCharger(Charger.of(tenantValue, "CHG-" + pad(charger, 2), "HUB-1", CHARGE_KW));
    }
    return new ShiftDraft(
        tenant,
        start,
        depot,
        Location.of("LOC-P", "Pickup"),
        Location.of("LOC-D", "Drop"),
        builder,
        hubId);
  }

  HubId hubId() {
    return hubId;
  }

  void cab(int index, long initialSocWh) {
    builder.addCab(
        new CabShift(
            new Vehicle(
                tenant, VehicleId.of("CAB-" + pad(index, 2)), BATTERY_WH, WH_PER_KM, CHARGE_KW),
            depot,
            initialSocWh,
            start));
  }

  void trip(int index, Instant pickupTime, int meters, int seconds) {
    builder.addTrip(
        new Trip(
            tenant,
            TripId.of("TRIP-" + pad(index, 2)),
            pickupTime,
            pickup,
            dropoff,
            meters,
            seconds,
            pickupTime.plusSeconds(seconds),
            "EMP-" + index));
  }

  void tariff(TariffSchedule schedule) {
    builder.addTariff(schedule);
  }

  PlannerInput build() {
    return builder.build();
  }

  static PlannerInput copy(PlannerInput source, ConsumptionModel model) {
    PlannerInput.Builder builder =
        PlannerInput.builder()
            .tenantId(source.tenantId())
            .horizon(source.horizonStart(), source.horizonEnd())
            .consumptionModel(model)
            .distanceProvider(source.distanceProvider())
            .chargingCurve(source.chargingCurve())
            .acOn(source.acOn())
            .idlePowerW(source.idlePowerW());
    source.hubs().forEach(builder::addHub);
    source.chargers().forEach(builder::addCharger);
    source.cabs().forEach(builder::addCab);
    source.trips().forEach(builder::addTrip);
    source.tariffs().forEach(builder::addTariff);
    return builder.build();
  }

  private static String pad(int value, int width) {
    String raw = Integer.toString(value);
    return "0".repeat(Math.max(0, width - raw.length())) + raw;
  }
}
