package com.moveinsync.ev.domain.location;

import java.util.Objects;

/**
 * Immutable identifier for a physical location (hub, office, or pickup/dropoff zone).
 *
 * @param value the unique location identifier string
 */
public record LocationId(String value) {
  public LocationId {
    Objects.requireNonNull(value, "LocationId value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("LocationId value must not be blank");
    }
  }

  public static LocationId of(String value) {
    return new LocationId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
