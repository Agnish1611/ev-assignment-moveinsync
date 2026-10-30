package com.moveinsync.ev.domain.charging;

/**
 * Immutable typed configuration for the deterministic two-segment charging curve (Decision D15).
 *
 * @param fastThresholdPercent percentage threshold (default 80%) separating fast and slow segments
 * @param slowRatePercent percentage of plug power (default 50%) delivered above the threshold
 */
public record ChargingCurveConfig(int fastThresholdPercent, int slowRatePercent) {

  public static final int DEFAULT_FAST_THRESHOLD_PERCENT = 80;
  public static final int DEFAULT_SLOW_RATE_PERCENT = 50;

  public ChargingCurveConfig {
    if (fastThresholdPercent <= 0 || fastThresholdPercent >= 100) {
      throw new IllegalArgumentException(
          "Fast threshold percent must be between 1 and 99: " + fastThresholdPercent);
    }
    if (slowRatePercent <= 0 || slowRatePercent >= 100) {
      throw new IllegalArgumentException(
          "Slow rate percent must be between 1 and 99: " + slowRatePercent);
    }
  }

  public static ChargingCurveConfig defaultConfig() {
    return new ChargingCurveConfig(DEFAULT_FAST_THRESHOLD_PERCENT, DEFAULT_SLOW_RATE_PERCENT);
  }
}
