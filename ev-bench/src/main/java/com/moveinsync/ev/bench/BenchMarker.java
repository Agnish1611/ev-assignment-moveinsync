package com.moveinsync.ev.bench;

import com.moveinsync.ev.domain.DomainMarker;

/**
 * Benchmark marker record establishing package presence.
 *
 * @param domainMarker marker referencing the domain model
 */
public record BenchMarker(DomainMarker domainMarker) {
  public BenchMarker {
    if (domainMarker == null) {
      throw new IllegalArgumentException("domainMarker must not be null");
    }
  }
}
