package com.moveinsync.ev.planner;

import com.moveinsync.ev.domain.DomainMarker;

/**
 * Planner marker record establishing package presence for architecture verification.
 *
 * @param domainMarker marker referencing the domain model
 */
public record PlannerMarker(DomainMarker domainMarker) {
  public PlannerMarker {
    if (domainMarker == null) {
      throw new IllegalArgumentException("domainMarker must not be null");
    }
  }
}
