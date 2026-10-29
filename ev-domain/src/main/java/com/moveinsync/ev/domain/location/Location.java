package com.moveinsync.ev.domain.location;

import java.util.Objects;

/**
 * Immutable domain representation of a location node.
 *
 * <p>To adhere strictly to privacy and log-hygiene rules, coordinates are represented as integer
 * micro-degrees and are never exposed in {@link #toString()}.
 *
 * @param id unique location identifier
 * @param name optional non-PII descriptive label (e.g. "Hub Alpha", "Office Park South")
 * @param latitudeMicros latitude in integer micro-degrees (1 degree = 1,000,000 micro-degrees)
 * @param longitudeMicros longitude in integer micro-degrees (1 degree = 1,000,000 micro-degrees)
 */
public record Location(LocationId id, String name, int latitudeMicros, int longitudeMicros) {

  public Location {
    Objects.requireNonNull(id, "Location id must not be null");
    Objects.requireNonNull(name, "Location name must not be null");
  }

  public static Location of(String id) {
    LocationId locId = LocationId.of(id);
    return new Location(locId, id, 0, 0);
  }

  public static Location of(String id, String name) {
    return new Location(LocationId.of(id), name, 0, 0);
  }

  public static Location of(String id, String name, int latitudeMicros, int longitudeMicros) {
    return new Location(LocationId.of(id), name, latitudeMicros, longitudeMicros);
  }

  /**
   * Safe string representation emitting only the opaque location identifier to satisfy PII rules.
   *
   * @return safe opaque location identifier
   */
  @Override
  public String toString() {
    return id.value();
  }
}
