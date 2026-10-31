package com.moveinsync.ev.sim;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.tariff.TariffSchedule;
import com.moveinsync.ev.domain.tariff.TariffWindow;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 300 cabs, 40 plugs, and 1,500 trips with a peak and an off-peak price. The morning hops fit on
 * the starting battery. Four 80 km trips do not once the morning hops are done, so those cabs
 * charge in the gap. 150 pickups share the 09:00 slot with no free cab. The same call always builds
 * the same snapshot. Time and space O(C + T).
 */
public final class PricedScale {

  public static final int MORNING_RUSH = 450;
  public static final int WAVE = 300;
  public static final int LONG_TRIPS = 4;
  public static final int LATE_TRIPS = 146;
  public static final long INITIAL_SOC_WH = 28_000L;
  public static final long PEAK_MICROS = 20_000_000L;
  public static final long OFF_PEAK_MICROS = 4_000_000L;

  private static final TenantId TENANT = ScaleScenario.TENANT;
  private static final ZoneId UTC = ZoneId.of("UTC");
  private static final LocalDate DAY = LocalDate.of(2026, 10, 30);
  private static final int[] PLUGS = {14, 13, 13};
  private static final long BATTERY_WH = 60_000L;

  private PricedScale() {
    throw new IllegalStateException("utility");
  }

  public static PlannerInput build() {
    PlannerInput.Builder builder =
        PlannerInput.builder()
            .tenantId(TENANT)
            .horizon(ScaleScenario.START, ScaleScenario.START.plusSeconds(12 * 3_600L))
            .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
            .consumptionModel(new ConstantConsumptionModel());
    Location[] depots = hubs(builder);
    cabs(builder, depots);
    int next = 0;
    next = trips(builder, next, MORNING_RUSH, 3_600L, 2_000, 600, 0L);
    next = trips(builder, next, WAVE, 8_400L, 2_000, 600, 0L);
    next = trips(builder, next, WAVE, 13_200L, 2_000, 600, 0L);
    next = trips(builder, next, WAVE, 18_000L, 2_000, 600, 0L);
    next = trips(builder, next, LONG_TRIPS, 24_600L, 80_000, 3_000, 600L);
    trips(builder, next, LATE_TRIPS, 26_400L, 2_000, 600, 0L);
    return builder.build();
  }

  private static Location[] hubs(PlannerInput.Builder builder) {
    Location[] depots = new Location[PLUGS.length];
    int charger = 0;
    for (int index = 0; index < PLUGS.length; index++) {
      String hubId = "HUB-" + (index + 1);
      depots[index] = Location.of("LOC-" + hubId, "Hub");
      builder.addHub(Hub.of(TENANT.value(), hubId, "Hub", depots[index], UTC, PLUGS[index]));
      for (int plug = 0; plug < PLUGS[index]; plug++) {
        builder.addCharger(Charger.of(TENANT.value(), "CHG-" + pad(charger, 2), hubId, 50));
        charger++;
      }
      tariff(builder, HubId.of(hubId));
    }
    return depots;
  }

  private static void tariff(PlannerInput.Builder builder, HubId hubId) {
    TariffWindow peak =
        new TariffWindow(hubId, LocalTime.of(8, 0), LocalTime.of(14, 0), PEAK_MICROS);
    TariffWindow offPeak =
        new TariffWindow(hubId, LocalTime.of(14, 0), LocalTime.of(20, 0), OFF_PEAK_MICROS);
    builder.addTariff(
        new TariffSchedule(
            hubId, List.of(peak.toUtcWindow(DAY, UTC), offPeak.toUtcWindow(DAY, UTC))));
  }

  private static void cabs(PlannerInput.Builder builder, Location[] depots) {
    for (int cab = 0; cab < ScaleScenario.CAB_COUNT; cab++) {
      builder.addCab(
          new CabShift(
              new Vehicle(TENANT, VehicleId.of("CAB-" + pad(cab, 3)), BATTERY_WH, 150L, 50),
              depots[cab % depots.length],
              INITIAL_SOC_WH,
              ScaleScenario.START));
    }
  }

  private static int trips(
      PlannerInput.Builder builder,
      int nextId,
      int count,
      long offsetSeconds,
      int meters,
      int durationSeconds,
      long slackSeconds) {
    for (int index = 0; index < count; index++) {
      Instant pickup = ScaleScenario.START.plusSeconds(offsetSeconds);
      String id = pad(nextId, 4);
      builder.addTrip(
          new Trip(
              TENANT,
              TripId.of("TRIP-" + id),
              pickup,
              Location.of("P" + id, "Pickup"),
              Location.of("D" + id, "Drop"),
              meters,
              durationSeconds,
              pickup.plusSeconds(durationSeconds + slackSeconds),
              "EMP-" + nextId));
      nextId++;
    }
    return nextId;
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
