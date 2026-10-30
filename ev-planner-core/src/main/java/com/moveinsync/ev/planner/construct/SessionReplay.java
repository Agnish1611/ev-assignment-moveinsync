package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.util.Optional;

/**
 * Replays a charging session onto a cab cursor. The stored start SoC has to equal the cab's SoC
 * after the deadhead to the hub, or the session is rejected.
 */
final class SessionReplay {

  static void adopt(
      CabCursor cursor, PlannerInput input, ChargingSession session, PlugCalendar plugs) {
    if (!cursor.vehicle().id().equals(session.vehicleId())) {
      throw mismatch();
    }
    Hub hub = hub(input, session);
    Charger charger = charger(input, session);
    DistanceDuration inbound = inbound(cursor, input, hub, session);
    arrival(cursor, input, inbound, session);
    if (!session.startTime().isBefore(session.endTime())
        || session.targetSocWh() < session.startSocWh()
        || session.targetSocWh() > cursor.vehicle().batteryCapacityWh()) {
      throw mismatch();
    }
    if (!charger.isAvailableDuring(session.startTime(), session.endTime())
        || !plugs.fits(hub, charger, session.startTime(), session.endTime())) {
      throw mismatch();
    }
    cursor.push(session, hub.location(), session.targetSocWh(), session.endTime());
    plugs.book(session);
  }

  private static Hub hub(PlannerInput input, ChargingSession session) {
    for (Hub hub : input.hubs()) {
      if (hub.id().equals(session.hubId())) {
        return hub;
      }
    }
    throw mismatch();
  }

  private static Charger charger(PlannerInput input, ChargingSession session) {
    for (Charger charger : input.chargers()) {
      if (charger.id().equals(session.chargerId()) && charger.hubId().equals(session.hubId())) {
        return charger;
      }
    }
    throw mismatch();
  }

  private static DistanceDuration inbound(
      CabCursor cursor, PlannerInput input, Hub hub, ChargingSession session) {
    Optional<DistanceDuration> leg = RoutePhysics.travel(input, cursor.location(), hub.location());
    if (leg.isEmpty()) {
      throw mismatch();
    }
    DistanceDuration travel = leg.orElseThrow();
    Instant depart = session.startTime().minusSeconds(travel.durationSeconds());
    if (cursor.freeAt().isAfter(depart)) {
      throw mismatch();
    }
    return travel;
  }

  private static void arrival(
      CabCursor cursor, PlannerInput input, DistanceDuration inbound, ChargingSession session) {
    long deadheadWh =
        RoutePhysics.energy(input, cursor.vehicle(), inbound.distanceMeters(), session.startTime());
    if (deadheadWh > cursor.socWh() || cursor.socWh() - deadheadWh != session.startSocWh()) {
      throw mismatch();
    }
  }

  private static IllegalArgumentException mismatch() {
    return new IllegalArgumentException("charging session does not match the cab state");
  }
}
