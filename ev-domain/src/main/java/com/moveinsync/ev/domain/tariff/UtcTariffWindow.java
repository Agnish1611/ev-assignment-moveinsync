package com.moveinsync.ev.domain.tariff;

import com.moveinsync.ev.domain.hub.HubId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * An electricity tariff rate resolved to an exact UTC epoch time interval.
 *
 * <p>Financial quantities are stored in integer micro-units (1 currency unit = 1,000,000
 * micro-units).
 *
 * @param hubId the hub to which this tariff applies
 * @param startUtc start time in UTC (inclusive)
 * @param endUtc end time in UTC (exclusive)
 * @param ratePerKwhMicros cost per kilowatt-hour (kWh) in integer micro-units
 */
public record UtcTariffWindow(
    HubId hubId, Instant startUtc, Instant endUtc, long ratePerKwhMicros) {

  public UtcTariffWindow {
    Objects.requireNonNull(hubId, "HubId must not be null");
    Objects.requireNonNull(startUtc, "startUtc must not be null");
    Objects.requireNonNull(endUtc, "endUtc must not be null");
    if (!startUtc.isBefore(endUtc)) {
      throw new IllegalArgumentException(
          "startUtc (" + startUtc + ") must be before endUtc (" + endUtc + ")");
    }
    if (ratePerKwhMicros < 0) {
      throw new IllegalArgumentException(
          "ratePerKwhMicros must be non-negative: " + ratePerKwhMicros);
    }
  }

  /**
   * Checks whether this UTC interval contains the specified instant.
   *
   * @param instant the instant to test
   * @return true if instant is within [startUtc, endUtc)
   */
  public boolean contains(Instant instant) {
    Objects.requireNonNull(instant, "Instant must not be null");
    return !instant.isBefore(startUtc) && instant.isBefore(endUtc);
  }

  /**
   * Checks whether this tariff window overlaps with a given time range.
   *
   * @param rangeStart range start instant
   * @param rangeEnd range end instant
   * @return true if the window overlaps with [rangeStart, rangeEnd)
   */
  public boolean overlaps(Instant rangeStart, Instant rangeEnd) {
    Objects.requireNonNull(rangeStart, "rangeStart must not be null");
    Objects.requireNonNull(rangeEnd, "rangeEnd must not be null");
    return startUtc.isBefore(rangeEnd) && endUtc.isAfter(rangeStart);
  }

  /**
   * Returns the duration of this window in seconds.
   *
   * @return window duration in seconds
   */
  public long durationSeconds() {
    return Duration.between(startUtc, endUtc).getSeconds();
  }

  /**
   * Calculates the charging cost in integer micro-units for the specified energy in Watt-hours.
   *
   * <p>Rounding is conservative: rounds UP so cost is never underestimated.
   *
   * @param energyWh energy charged in Watt-hours
   * @return cost in integer micro-units
   */
  public long calculateCostMicros(long energyWh) {
    if (energyWh < 0) {
      throw new IllegalArgumentException("energyWh must be non-negative: " + energyWh);
    }
    if (energyWh == 0 || ratePerKwhMicros == 0) {
      return 0L;
    }
    long product = Math.multiplyExact(energyWh, ratePerKwhMicros);
    return (product + 999L) / 1000L;
  }
}
