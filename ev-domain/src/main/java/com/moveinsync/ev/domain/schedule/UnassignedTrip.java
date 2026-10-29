package com.moveinsync.ev.domain.schedule;

import com.moveinsync.ev.domain.trip.Trip;
import java.util.Objects;

/**
 * Diagnostic record describing a trip routed to backup demand along with root-cause reason.
 *
 * @param trip the unassigned trip
 * @param reasonCode standard classification code
 * @param explanation human-readable or structured explanation string (strictly non-PII)
 */
public record UnassignedTrip(Trip trip, UnassignedReasonCode reasonCode, String explanation) {

  public UnassignedTrip {
    Objects.requireNonNull(trip, "trip must not be null");
    Objects.requireNonNull(reasonCode, "reasonCode must not be null");
    Objects.requireNonNull(explanation, "explanation must not be null");
  }

  public static UnassignedTrip of(Trip trip, UnassignedReasonCode reasonCode, String explanation) {
    return new UnassignedTrip(trip, reasonCode, explanation);
  }
}
