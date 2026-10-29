package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.DomainMarker;

/**
 * Validator baseline marker to establish package presence for architecture verification.
 *
 * @param domainMarker marker referencing the domain model
 */
public record ValidatorMarker(DomainMarker domainMarker) {
  public ValidatorMarker {
    if (domainMarker == null) {
      throw new IllegalArgumentException("domainMarker must not be null");
    }
  }
}
