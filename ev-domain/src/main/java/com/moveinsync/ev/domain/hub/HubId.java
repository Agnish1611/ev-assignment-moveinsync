package com.moveinsync.ev.domain.hub;

import java.util.Objects;

/**
 * Immutable identifier for an EV charging hub.
 *
 * @param value the unique hub identifier string
 */
public record HubId(String value) {
  public HubId {
    Objects.requireNonNull(value, "HubId value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("HubId value must not be blank");
    }
  }

  public static HubId of(String value) {
    return new HubId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
