package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Half-open plug bookings for one construction or repair pass. A slot is free when the plug is idle
 * and the hub still has a charger left at that instant.
 */
final class PlugCalendar {

  private final List<Booking> bookings = new ArrayList<>();

  boolean fits(Hub hub, Charger charger, Instant start, Instant end) {
    return blockedUntil(hub, charger, start, end) < 0L;
  }

  /**
   * Earliest instant at which a blocked slot might be tried again. Empty when the slot is not
   * blocked.
   */
  Optional<Instant> retryAfter(Hub hub, Charger charger, Instant start, Instant end) {
    long until = blockedUntil(hub, charger, start, end);
    if (until < 0L) {
      return Optional.empty();
    }
    return Optional.of(Instant.ofEpochSecond(until));
  }

  void book(ChargingSession session) {
    bookings.add(
        new Booking(
            session.hubId().value(),
            session.chargerId().value(),
            session.startTime().getEpochSecond(),
            session.endTime().getEpochSecond()));
  }

  private long blockedUntil(Hub hub, Charger charger, Instant start, Instant end) {
    long from = start.getEpochSecond();
    long to = end.getEpochSecond();
    long plugFree = -1L;
    int hubHits = 0;
    long earliestHubEnd = Long.MAX_VALUE;
    for (Booking booking : bookings) {
      if (from >= booking.endExclusive || booking.startInclusive >= to) {
        continue;
      }
      if (booking.chargerId.equals(charger.id().value()) && booking.endExclusive > plugFree) {
        plugFree = booking.endExclusive;
      }
      if (booking.hubId.equals(hub.id().value())) {
        hubHits++;
        if (booking.endExclusive < earliestHubEnd) {
          earliestHubEnd = booking.endExclusive;
        }
      }
    }
    if (plugFree >= 0L) {
      return plugFree;
    }
    if (hubHits >= hub.chargerCount()) {
      return earliestHubEnd;
    }
    return -1L;
  }

  private record Booking(String hubId, String chargerId, long startInclusive, long endExclusive) {}
}
