package com.moveinsync.ev.planner.input;

import com.moveinsync.ev.domain.charging.ChargingCurve;
import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.consumption.ConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.distance.MatrixDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.OutageWindow;
import com.moveinsync.ev.domain.tariff.TariffSchedule;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.domain.trip.Trip;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * SHA-256 of a canonical snapshot of a {@link PlannerInput}.
 *
 * <p>Time O(n log n) for the sorted matrix rows already produced by the provider, plus O(n) to walk
 * cabs, trips, hubs, chargers, and tariffs. Space O(n) for the canonical text. Only constant
 * consumption and constant-speed or matrix distance providers are accepted. Anything else fails
 * closed so two different models cannot share a hash.
 */
final class InputDigest {

  private InputDigest() {}

  static String of(PlannerInput input) {
    StringBuilder text = new StringBuilder();
    text.append("planner-input-v1\n");
    appendHeader(text, input);
    appendCabs(text, input);
    appendTrips(text, input);
    appendHubs(text, input);
    appendChargers(text, input);
    appendTariffs(text, input);
    appendDistance(text, input.distanceProvider(), newIdentitySet());
    appendConsumption(text, input.consumptionModel());
    appendCurve(text, input.chargingCurve(), input.acOn(), input.idlePowerW());
    return sha256(text.toString());
  }

  private static void appendHeader(StringBuilder text, PlannerInput input) {
    text.append("tenant=").append(input.tenantId().value()).append('\n');
    text.append("horizon=")
        .append(input.horizonStart().getEpochSecond())
        .append(',')
        .append(input.horizonEnd().getEpochSecond())
        .append('\n');
  }

  private static void appendCabs(StringBuilder text, PlannerInput input) {
    for (CabShift cab : input.cabs()) {
      text.append("cab=")
          .append(cab.vehicle().id().value())
          .append(',')
          .append(cab.vehicle().batteryCapacityWh())
          .append(',')
          .append(cab.vehicle().baseConsumptionWhPerKm())
          .append(',')
          .append(cab.vehicle().maxChargeKw())
          .append(',')
          .append(cab.initialSocWh())
          .append(',')
          .append(cab.availableFrom().getEpochSecond())
          .append(',')
          .append(cab.startLocation().id().value())
          .append('\n');
    }
  }

  private static void appendTrips(StringBuilder text, PlannerInput input) {
    for (Trip trip : input.trips()) {
      text.append("trip=")
          .append(trip.id().value())
          .append(',')
          .append(trip.startTs().getEpochSecond())
          .append(',')
          .append(trip.distanceMeters())
          .append(',')
          .append(trip.estimatedDurationSeconds())
          .append(',')
          .append(trip.latestFinishTs().getEpochSecond())
          .append(',')
          .append(trip.fromLocation().id().value())
          .append(',')
          .append(trip.toLocation().id().value())
          .append(',')
          .append(trip.employeeRef())
          .append('\n');
    }
  }

  private static void appendHubs(StringBuilder text, PlannerInput input) {
    for (Hub hub : input.hubs()) {
      text.append("hub=")
          .append(hub.id().value())
          .append(',')
          .append(hub.chargerCount())
          .append(',')
          .append(hub.location().id().value())
          .append(',')
          .append(hub.timeZone().getId())
          .append('\n');
    }
  }

  private static void appendChargers(StringBuilder text, PlannerInput input) {
    for (Charger charger : input.chargers()) {
      text.append("charger=")
          .append(charger.id().value())
          .append(',')
          .append(charger.hubId().value())
          .append(',')
          .append(charger.powerKw())
          .append(',')
          .append(charger.status().name())
          .append('\n');
      for (OutageWindow outage : charger.outageWindows()) {
        text.append("outage=")
            .append(charger.id().value())
            .append(',')
            .append(outage.startInclusive().getEpochSecond())
            .append(',')
            .append(outage.endExclusive().getEpochSecond())
            .append(',')
            .append(outage.reason())
            .append('\n');
      }
    }
  }

  private static void appendTariffs(StringBuilder text, PlannerInput input) {
    for (TariffSchedule schedule : input.tariffs()) {
      for (UtcTariffWindow window : schedule.windows()) {
        text.append("tariff=")
            .append(schedule.hubId().value())
            .append(',')
            .append(window.startUtc().getEpochSecond())
            .append(',')
            .append(window.endUtc().getEpochSecond())
            .append(',')
            .append(window.ratePerKwhMicros())
            .append('\n');
      }
    }
  }

  private static void appendDistance(
      StringBuilder text, DistanceProvider provider, Set<DistanceProvider> seen) {
    if (!seen.add(provider)) {
      throw new IllegalArgumentException("distance provider cycle cannot be hashed");
    }
    if (provider instanceof ConstantSpeedDistanceProvider speed) {
      text.append("distance.constant=")
          .append(speed.defaultDistanceMeters())
          .append(',')
          .append(speed.speedMetersPerSecond())
          .append('\n');
      return;
    }
    if (provider instanceof MatrixDistanceProvider matrix) {
      appendMatrix(text, matrix, seen);
      return;
    }
    throw new IllegalArgumentException(
        "distance provider cannot be hashed: " + provider.getClass().getSimpleName());
  }

  private static void appendMatrix(
      StringBuilder text, MatrixDistanceProvider matrix, Set<DistanceProvider> seen) {
    text.append("distance.matrix\n");
    for (var entry : matrix.entries()) {
      text.append(entry.getKey().from().value())
          .append('>')
          .append(entry.getKey().to().value())
          .append('=')
          .append(entry.getValue().distanceMeters())
          .append(',')
          .append(entry.getValue().durationSeconds())
          .append('\n');
    }
    matrix
        .fallback()
        .ifPresent(
            fallback -> {
              text.append("distance.fallback\n");
              appendDistance(text, fallback, seen);
            });
  }

  private static void appendConsumption(StringBuilder text, ConsumptionModel model) {
    if (model instanceof ConstantConsumptionModel constant) {
      text.append("consumption.constant=")
          .append(constant.trafficFactorBasisPoints())
          .append(',')
          .append(constant.acOnFactorBasisPoints())
          .append('\n');
      return;
    }
    throw new IllegalArgumentException(
        "consumption model cannot be hashed: " + model.getClass().getSimpleName());
  }

  private static void appendCurve(
      StringBuilder text, ChargingCurve curve, boolean acOn, long idlePowerW) {
    text.append("curve=")
        .append(curve.fastThresholdPercent())
        .append(',')
        .append(curve.slowRatePercent())
        .append('\n');
    text.append("ac=").append(acOn).append('\n');
    text.append("idleW=").append(idlePowerW).append('\n');
  }

  private static Set<DistanceProvider> newIdentitySet() {
    return java.util.Collections.newSetFromMap(new IdentityHashMap<>());
  }

  private static String sha256(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required", ex);
    }
  }
}
