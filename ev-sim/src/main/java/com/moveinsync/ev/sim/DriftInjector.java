package com.moveinsync.ev.sim;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.consumption.StandardConsumptionModel;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Applies a seeded traffic change and one reading per cab. Time and space O(C) for C cabs. The same
 * fleet and seed always produce the same sample. Sensor noise stays inside the pack, one cab
 * reports an impossible SoC, and one cab goes silent.
 */
public final class DriftInjector {

  static final int NOISE_HALF_WH = 400;
  private static final int NOISE_SPAN = NOISE_HALF_WH * 2 + 1;
  private static final int OUTLIER_ABOVE_PACK_WH = 5_000;
  private static final int[] TRAFFIC_BASIS_POINTS = {8_000, 10_000, 12_000, 15_000};

  private DriftInjector() {
    throw new IllegalStateException("utility");
  }

  public static DriftSample inject(PlannerInput fleet, long seed) {
    Objects.requireNonNull(fleet, "fleet must not be null");
    List<CabShift> cabs = fleet.cabs();
    if (cabs.size() < 2) {
      throw new IllegalArgumentException("drift injection needs at least two cabs");
    }
    SeededSequence random = new SeededSequence(seed);
    int traffic = TRAFFIC_BASIS_POINTS[random.nextInt(TRAFFIC_BASIS_POINTS.length)];
    int outlier = random.nextInt(cabs.size());
    int silent = (outlier + 1 + random.nextInt(cabs.size() - 1)) % cabs.size();
    List<CabReading> readings = new ArrayList<>();
    for (int index = 0; index < cabs.size(); index++) {
      readings.add(reading(cabs.get(index), fleet, random, index, outlier, silent));
    }
    return new DriftSample(traffic, readings);
  }

  /**
   * Returns the same fleet with a constant traffic factor. Air-conditioning basis points are kept
   * when the current model is constant.
   */
  public static PlannerInput withTraffic(PlannerInput fleet, int trafficFactorBasisPoints) {
    Objects.requireNonNull(fleet, "fleet must not be null");
    if (trafficFactorBasisPoints <= 0) {
      throw new IllegalArgumentException(
          "trafficFactorBasisPoints must be positive: " + trafficFactorBasisPoints);
    }
    int ac = StandardConsumptionModel.DEFAULT_AC_FACTOR_BASIS_POINTS;
    if (fleet.consumptionModel() instanceof ConstantConsumptionModel constant) {
      ac = constant.acOnFactorBasisPoints();
    }
    return ShiftDraft.copy(fleet, new ConstantConsumptionModel(trafficFactorBasisPoints, ac));
  }

  private static CabReading reading(
      CabShift cab, PlannerInput fleet, SeededSequence random, int index, int outlier, int silent) {
    String cabId = cab.vehicle().id().value();
    Instant observedAt = fleet.horizonStart().plusSeconds(60);
    if (index == silent) {
      return new CabReading(cabId, observedAt, OptionalLong.empty(), CabReading.Kind.SILENT);
    }
    if (index == outlier) {
      long reported = cab.vehicle().batteryCapacityWh() + OUTLIER_ABOVE_PACK_WH;
      return new CabReading(cabId, observedAt, OptionalLong.of(reported), CabReading.Kind.OUTLIER);
    }
    int delta = random.nextInt(NOISE_SPAN) - NOISE_HALF_WH;
    long reported = clamp(cab.initialSocWh() + delta, cab.vehicle().batteryCapacityWh());
    return new CabReading(cabId, observedAt, OptionalLong.of(reported), CabReading.Kind.NOISE);
  }

  private static long clamp(long socWh, long capacityWh) {
    if (socWh < 0L) {
      return 0L;
    }
    if (socWh > capacityWh) {
      return capacityWh;
    }
    return socWh;
  }
}
