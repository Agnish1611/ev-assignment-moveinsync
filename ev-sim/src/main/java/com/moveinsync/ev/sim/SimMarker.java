package com.moveinsync.ev.sim;

import com.moveinsync.ev.domain.DomainMarker;

/**
 * Simulator marker record establishing package presence.
 *
 * @param domainMarker marker referencing the domain model
 */
public record SimMarker(DomainMarker domainMarker) {
  public SimMarker {
    if (domainMarker == null) {
      throw new IllegalArgumentException("domainMarker must not be null");
    }
  }
}
