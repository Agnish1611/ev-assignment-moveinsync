package com.moveinsync.ev.sim;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.time.ZoneId;

/**
 * The case-study fleet: 300 cabs, 40 chargers, and 1,500 trips. Identifiers are opaque. The same
 * call always builds the same snapshot.
 */
public final class ScaleScenario {

  public static final int CAB_COUNT = 300;
  public static final int CHARGER_COUNT = 40;
  public static final int TRIPS_PER_CAB = 5;
  public static final int TRIP_COUNT = CAB_COUNT * TRIPS_PER_CAB;
  public static final TenantId TENANT = TenantId.of("tenant-scale");
  public static final Instant START = Instant.parse("2026-10-30T08:00:00Z");
  public static final Instant END = START.plusSeconds(28_800);

  private static final long BATTERY_WH = 60_000L;
  private static final long INITIAL_SOC_WH = 50_000L;
  private static final int TRIP_METERS = 2_000;
  private static final int TRIP_SECONDS = 600;
  private static final int SLOT_GAP_SECONDS = 2_000;

  private ScaleScenario() {
    throw new IllegalStateException("utility");
  }

  public static PlannerInput build() {
    return fleet(CAB_COUNT, CHARGER_COUNT, TRIPS_PER_CAB);
  }

  /**
   * A smaller copy of the evening fleet. Cab, charger, and trip identifiers use the same padding as
   * {@link #build()}. Time and space O(C + T).
   */
  public static PlannerInput fleet(int cabs, int chargers, int tripsPerCab) {
    if (cabs <= 0 || cabs > CAB_COUNT) {
      throw new IllegalArgumentException("cabs must be from 1 to " + CAB_COUNT + ": " + cabs);
    }
    if (chargers <= 0 || chargers > CHARGER_COUNT) {
      throw new IllegalArgumentException(
          "chargers must be from 1 to " + CHARGER_COUNT + ": " + chargers);
    }
    if (tripsPerCab <= 0 || tripsPerCab > TRIPS_PER_CAB) {
      throw new IllegalArgumentException(
          "tripsPerCab must be from 1 to " + TRIPS_PER_CAB + ": " + tripsPerCab);
    }
    Location depot = Location.of("LOC-HUB", "Hub");
    Location pickup = Location.of("LOC-P", "Pickup");
    Location dropoff = Location.of("LOC-D", "Drop");
    Hub hub = Hub.of(TENANT.value(), "HUB-1", "Hub", depot, ZoneId.of("UTC"), chargers);
    PlannerInput.Builder builder =
        PlannerInput.builder()
            .tenantId(TENANT)
            .horizon(START, END)
            .addHub(hub)
            .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
            .consumptionModel(new ConstantConsumptionModel());
    for (int charger = 0; charger < chargers; charger++) {
      builder.addCharger(Charger.of(TENANT.value(), "CHG-" + pad(charger, 2), "HUB-1", 50));
    }
    for (int cab = 0; cab < cabs; cab++) {
      builder.addCab(
          new CabShift(
              new Vehicle(TENANT, VehicleId.of("CAB-" + pad(cab, 3)), BATTERY_WH, 150L, 50),
              depot,
              INITIAL_SOC_WH,
              START));
    }
    for (int cab = 0; cab < cabs; cab++) {
      for (int slot = 0; slot < tripsPerCab; slot++) {
        Instant pickupTime = START.plusSeconds(600L + (long) slot * SLOT_GAP_SECONDS);
        builder.addTrip(
            new Trip(
                TENANT,
                TripId.of("TRIP-" + pad(cab * tripsPerCab + slot, 4)),
                pickupTime,
                pickup,
                dropoff,
                TRIP_METERS,
                TRIP_SECONDS,
                pickupTime.plusSeconds(TRIP_SECONDS),
                "EMP-" + cab));
      }
    }
    return builder.build();
  }

  private static String pad(int value, int width) {
    String raw = Integer.toString(value);
    StringBuilder builder = new StringBuilder();
    while (builder.length() + raw.length() < width) {
      builder.append('0');
    }
    builder.append(raw);
    return builder.toString();
  }
}
