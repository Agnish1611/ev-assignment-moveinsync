package com.moveinsync.ev.domain.trip;

import java.util.Objects;

/**
 * Immutable identifier for a trip request.
 *
 * @param value the unique trip identifier string
 */
public record TripId(String value) {
  public TripId {
    Objects.requireNonNull(value, "TripId value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("TripId value must not be blank");
    }
  }

  public static TripId of(String value) {
    return new TripId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
