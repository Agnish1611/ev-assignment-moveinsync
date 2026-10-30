package com.moveinsync.ev.planner.telemetry;

/**
 * Integer bounds for accepting a cab reading. Rates are watt-hours per second.
 *
 * @param maxRiseWhPerSecond largest believable charge rate
 * @param maxDropWhPerSecond largest believable discharge rate
 * @param hysteresisWh drift is emitted only when the gap to the plan reaches this
 * @param cooldownSeconds minimum gap between drift triggers for one cab
 * @param silenceSeconds readings older than this are decayed
 * @param decayWhPerSecond conservative energy loss while a cab is silent
 * @param criticalSocWh readings below this are kept even when the jump looks impossible
 */
public record TelemetryPolicy(
    long maxRiseWhPerSecond,
    long maxDropWhPerSecond,
    long hysteresisWh,
    long cooldownSeconds,
    long silenceSeconds,
    long decayWhPerSecond,
    long criticalSocWh) {

  public TelemetryPolicy {
    if (maxRiseWhPerSecond <= 0L || maxDropWhPerSecond <= 0L || decayWhPerSecond <= 0L) {
      throw new IllegalArgumentException("rates must be positive");
    }
    if (hysteresisWh < 0L || criticalSocWh < 0L || cooldownSeconds < 0L) {
      throw new IllegalArgumentException("thresholds must be non-negative");
    }
    if (silenceSeconds <= 0L) {
      throw new IllegalArgumentException("silenceSeconds must be positive");
    }
  }

  /**
   * Charge near 100 Wh/s, discharge near 50 Wh/s, 1 kWh hysteresis, 30 s cooldown, 2 min silence,
   * 10 Wh/s decay, and a 9 kWh critical floor.
   */
  public static TelemetryPolicy standard() {
    return new TelemetryPolicy(100L, 50L, 1_000L, 30L, 120L, 10L, 9_000L);
  }
}
