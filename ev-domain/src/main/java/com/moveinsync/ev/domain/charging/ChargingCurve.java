package com.moveinsync.ev.domain.charging;

/**
 * Deterministic two-segment piecewise-linear EV charging curve model.
 *
 * <p>The model defines two distinct linear regimes:
 *
 * <ul>
 *   <li>Fast segment: from 0% up to 80% State of Charge (SoC) charging at full plug power (100%).
 *   <li>Slow segment: from 80% to 100% SoC charging at a reduced rate (default 50% of plug power).
 * </ul>
 *
 * <p>All calculations use integer arithmetic only (energy in Wh, power in kW/Watts, time in
 * seconds). Rounding is conservative:
 *
 * <ul>
 *   <li>Duration required to charge rounds UP to avoid underestimating plug occupation.
 *   <li>Energy delivered during an elapsed duration rounds DOWN to avoid overestimating battery
 *       level.
 * </ul>
 *
 * <p>Complexity:
 *
 * <ul>
 *   <li>Time complexity: O(1) for all methods.
 *   <li>Space complexity: O(1).
 * </ul>
 */
public final class ChargingCurve {

  public static final int DEFAULT_FAST_THRESHOLD_PERCENT = 80;
  public static final int DEFAULT_SLOW_RATE_PERCENT = 50;

  private static final ChargingCurve DEFAULT_INSTANCE =
      new ChargingCurve(DEFAULT_FAST_THRESHOLD_PERCENT, DEFAULT_SLOW_RATE_PERCENT);

  private final int fastThresholdPercent;
  private final int slowRatePercent;

  public ChargingCurve() {
    this(DEFAULT_FAST_THRESHOLD_PERCENT, DEFAULT_SLOW_RATE_PERCENT);
  }

  public ChargingCurve(ChargingCurveConfig config) {
    this(
        java.util.Objects.requireNonNull(config, "config must not be null").fastThresholdPercent(),
        config.slowRatePercent());
  }

  public ChargingCurve(int fastThresholdPercent, int slowRatePercent) {
    if (fastThresholdPercent <= 0 || fastThresholdPercent >= 100) {
      throw new IllegalArgumentException(
          "Fast threshold percent must be between 1 and 99: " + fastThresholdPercent);
    }
    if (slowRatePercent <= 0 || slowRatePercent >= 100) {
      throw new IllegalArgumentException(
          "Slow rate percent must be between 1 and 99: " + slowRatePercent);
    }
    this.fastThresholdPercent = fastThresholdPercent;
    this.slowRatePercent = slowRatePercent;
  }

  public static ChargingCurve defaultCurve() {
    return DEFAULT_INSTANCE;
  }

  public int fastThresholdPercent() {
    return fastThresholdPercent;
  }

  public int slowRatePercent() {
    return slowRatePercent;
  }

  /**
   * Calculates the threshold SoC in Watt-hours separating the fast and slow segments.
   *
   * @param batteryCapacityWh total battery capacity in Wh
   * @return threshold SoC in Wh (80% of capacity)
   */
  public long calculateThresholdSocWh(long batteryCapacityWh) {
    if (batteryCapacityWh <= 0) {
      throw new IllegalArgumentException("Battery capacity must be positive: " + batteryCapacityWh);
    }
    return Math.multiplyExact(batteryCapacityWh, (long) fastThresholdPercent) / 100L;
  }

  /**
   * Calculates the deterministic charging session duration in seconds required to go from {@code
   * startSocWh} to {@code targetSocWh} at {@code plugPowerKw}.
   *
   * <p>Enforces the rule that plug power is {@code min(chargerKw, vehicleMaxKw)}. Rounding is
   * conservative: rounds UP to the next whole second.
   *
   * @param startSocWh initial battery SoC in Wh
   * @param targetSocWh target battery SoC in Wh
   * @param batteryCapacityWh total battery capacity in Wh
   * @param plugPowerKw effective plug power in kW (min of charger and vehicle)
   * @return duration in seconds (conservative rounded UP)
   */
  public long calculateDurationSeconds(
      long startSocWh, long targetSocWh, long batteryCapacityWh, int plugPowerKw) {
    validateInputs(startSocWh, targetSocWh, batteryCapacityWh, plugPowerKw);

    if (startSocWh == targetSocWh) {
      return 0L;
    }

    long thresholdSocWh = calculateThresholdSocWh(batteryCapacityWh);

    // Segment 1: fast charging from startSocWh up to min(targetSocWh, thresholdSocWh)
    long fastEndSocWh = Math.min(targetSocWh, thresholdSocWh);
    long fastEnergyWh = Math.max(0L, fastEndSocWh - startSocWh);

    // Segment 2: slow charging from max(startSocWh, thresholdSocWh) up to targetSocWh
    long slowStartSocWh = Math.max(startSocWh, thresholdSocWh);
    long slowEnergyWh = Math.max(0L, targetSocWh - slowStartSocWh);

    long fastPowerWatts = Math.multiplyExact((long) plugPowerKw, 1000L);
    long slowPowerWatts =
        Math.max(1L, Math.multiplyExact(fastPowerWatts, (long) slowRatePercent) / 100L);

    // Energy * 3600 seconds/hr / Power in Watts. Conservative round UP: (num + den - 1) / den
    long fastSeconds = 0L;
    if (fastEnergyWh > 0) {
      long fastNumerator = Math.multiplyExact(fastEnergyWh, 3600L);
      fastSeconds = (fastNumerator + fastPowerWatts - 1L) / fastPowerWatts;
    }

    long slowSeconds = 0L;
    if (slowEnergyWh > 0) {
      long slowNumerator = Math.multiplyExact(slowEnergyWh, 3600L);
      slowSeconds = (slowNumerator + slowPowerWatts - 1L) / slowPowerWatts;
    }

    return Math.addExact(fastSeconds, slowSeconds);
  }

  /**
   * Calculates the resulting SoC in Watt-hours achieved after charging for {@code durationSeconds}.
   *
   * <p>Rounding is conservative: rounds DOWN so we never overestimate the delivered energy.
   *
   * @param startSocWh initial battery SoC in Wh
   * @param durationSeconds charging duration in seconds
   * @param batteryCapacityWh total battery capacity in Wh
   * @param plugPowerKw effective plug power in kW
   * @return resulting SoC in Wh (bounded by batteryCapacityWh)
   */
  public long calculateTargetSocWh(
      long startSocWh, long durationSeconds, long batteryCapacityWh, int plugPowerKw) {
    if (batteryCapacityWh <= 0) {
      throw new IllegalArgumentException("Battery capacity must be positive: " + batteryCapacityWh);
    }
    if (plugPowerKw <= 0) {
      throw new IllegalArgumentException("Plug power must be positive: " + plugPowerKw);
    }
    if (startSocWh < 0 || startSocWh > batteryCapacityWh) {
      throw new IllegalArgumentException(
          "startSocWh (" + startSocWh + ") must be within [0, " + batteryCapacityWh + "]");
    }
    if (durationSeconds <= 0) {
      return startSocWh;
    }

    long thresholdSocWh = calculateThresholdSocWh(batteryCapacityWh);
    long fastPowerWatts = Math.multiplyExact((long) plugPowerKw, 1000L);
    long slowPowerWatts =
        Math.max(1L, Math.multiplyExact(fastPowerWatts, (long) slowRatePercent) / 100L);

    if (startSocWh < thresholdSocWh) {
      long fastNeededEnergyWh = thresholdSocWh - startSocWh;
      long timeToThresholdSeconds =
          (Math.multiplyExact(fastNeededEnergyWh, 3600L) + fastPowerWatts - 1L) / fastPowerWatts;

      if (durationSeconds <= timeToThresholdSeconds) {
        long deliveredWh = Math.multiplyExact(durationSeconds, fastPowerWatts) / 3600L;
        return Math.min(thresholdSocWh, Math.addExact(startSocWh, deliveredWh));
      } else {
        long remainingSeconds = durationSeconds - timeToThresholdSeconds;
        long slowDeliveredWh = Math.multiplyExact(remainingSeconds, slowPowerWatts) / 3600L;
        return Math.min(batteryCapacityWh, Math.addExact(thresholdSocWh, slowDeliveredWh));
      }
    } else {
      long slowDeliveredWh = Math.multiplyExact(durationSeconds, slowPowerWatts) / 3600L;
      return Math.min(batteryCapacityWh, Math.addExact(startSocWh, slowDeliveredWh));
    }
  }

  private void validateInputs(
      long startSocWh, long targetSocWh, long batteryCapacityWh, int plugPowerKw) {
    if (batteryCapacityWh <= 0) {
      throw new IllegalArgumentException("Battery capacity must be positive: " + batteryCapacityWh);
    }
    if (plugPowerKw <= 0) {
      throw new IllegalArgumentException("Plug power must be positive: " + plugPowerKw);
    }
    if (startSocWh < 0 || startSocWh > batteryCapacityWh) {
      throw new IllegalArgumentException(
          "startSocWh (" + startSocWh + ") must be within [0, " + batteryCapacityWh + "]");
    }
    if (targetSocWh < 0 || targetSocWh > batteryCapacityWh) {
      throw new IllegalArgumentException(
          "targetSocWh (" + targetSocWh + ") must be within [0, " + batteryCapacityWh + "]");
    }
    if (targetSocWh < startSocWh) {
      throw new IllegalArgumentException(
          "targetSocWh (" + targetSocWh + ") cannot be less than startSocWh (" + startSocWh + ")");
    }
  }
}
