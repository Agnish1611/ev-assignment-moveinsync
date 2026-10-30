package com.moveinsync.ev.domain.hub;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable value object representing a planned or unplanned charger outage window.
 *
 * <p>Uses half-open {@code [startInclusive, endExclusive)} interval semantics as mandated by
 * Decision D4.
 *
 * @param startInclusive start instant of the outage (inclusive)
 * @param endExclusive end instant of the outage (exclusive)
 * @param reason descriptive reason for the outage (maintenance, curtailment, hardware fault)
 */
public record OutageWindow(Instant startInclusive, Instant endExclusive, String reason) {

  public OutageWindow {
    Objects.requireNonNull(startInclusive, "startInclusive must not be null");
    Objects.requireNonNull(endExclusive, "endExclusive must not be null");
    Objects.requireNonNull(reason, "reason must not be null");

    if (!startInclusive.isBefore(endExclusive)) {
      throw new IllegalArgumentException(
          "startInclusive ("
              + startInclusive
              + ") must be strictly before endExclusive ("
              + endExclusive
              + ")");
    }
  }

  /**
   * Evaluates whether a given time interval {@code [from, to)} overlaps with this outage window.
   *
   * @param from start instant of the interval (inclusive)
   * @param to end instant of the interval (exclusive)
   * @return true if the intervals overlap, false otherwise
   */
  public boolean overlaps(Instant from, Instant to) {
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");
    return from.isBefore(endExclusive) && startInclusive.isBefore(to);
  }
}
