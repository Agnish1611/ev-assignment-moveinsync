package com.moveinsync.ev.domain.hub;

import java.util.Objects;

/**
 * Immutable identifier for an individual charging plug.
 *
 * @param value the unique charger identifier string
 */
public record ChargerId(String value) {
  public ChargerId {
    Objects.requireNonNull(value, "ChargerId value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("ChargerId value must not be blank");
    }
  }

  public static ChargerId of(String value) {
    return new ChargerId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
