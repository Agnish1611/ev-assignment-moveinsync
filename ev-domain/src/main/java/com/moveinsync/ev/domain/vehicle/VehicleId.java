package com.moveinsync.ev.domain.vehicle;

import java.util.Objects;

/**
 * Immutable identifier for an electric vehicle in the fleet.
 *
 * @param value the unique vehicle identifier string
 */
public record VehicleId(String value) {
  public VehicleId {
    Objects.requireNonNull(value, "VehicleId value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("VehicleId value must not be blank");
    }
  }

  public static VehicleId of(String value) {
    return new VehicleId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
