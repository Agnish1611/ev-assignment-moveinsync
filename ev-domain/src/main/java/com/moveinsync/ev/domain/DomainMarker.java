package com.moveinsync.ev.domain;

/**
 * Domain marker record to establish baseline package presence for architectural checks.
 *
 * @param version the domain schema version
 */
public record DomainMarker(int version) {
  public DomainMarker {
    if (version < 1) {
      throw new IllegalArgumentException("Version must be positive");
    }
  }
}
