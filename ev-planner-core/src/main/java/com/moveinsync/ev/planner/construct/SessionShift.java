package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.util.Optional;

/**
 * Slides one session onto a cheaper contiguous block. The trip stays on the same cab.
 *
 * <p>Time O(H · P · K) for H hubs, P plugs, and K start seeds. Space O(K). A move is accepted only
 * when the curve, the plug calendar, and the following trip all still hold and the tariff cost is
 * strictly lower.
 */
final class SessionShift {

  static Optional<ChargingSession> select(
      CabCursor cursor,
      PlannerInput input,
      Trip trip,
      ChargingSession current,
      int floorPercent,
      PlugCalendar plugs) {
    Optional<ChargingSession> best = search(cursor, input, trip, floorPercent, plugs);
    if (best.isPresent() && best.orElseThrow().costMicros() < current.costMicros()) {
      return best;
    }
    if (samePlace(cursor, input, trip, current, floorPercent, plugs)) {
      return Optional.of(current);
    }
    return best;
  }

  private static Optional<ChargingSession> search(
      CabCursor cursor, PlannerInput input, Trip trip, int floorPercent, PlugCalendar plugs) {
    Optional<ChargingSession> best = Optional.empty();
    for (Hub hub : SessionCatalog.hubs(input)) {
      for (Charger charger : SessionCatalog.chargers(input, hub)) {
        best = walk(cursor, input, trip, floorPercent, plugs, hub, charger, best);
      }
    }
    return best;
  }

  private static Optional<ChargingSession> walk(
      CabCursor cursor,
      PlannerInput input,
      Trip trip,
      int floorPercent,
      PlugCalendar plugs,
      Hub hub,
      Charger charger,
      Optional<ChargingSession> best) {
    if (!charger.status().isAvailable()) {
      return best;
    }
    Optional<DistanceDuration> inbound =
        RoutePhysics.travel(input, cursor.location(), hub.location());
    Optional<DistanceDuration> outbound =
        RoutePhysics.travel(input, hub.location(), trip.fromLocation());
    if (inbound.isEmpty() || outbound.isEmpty()) {
      return best;
    }
    Instant earliest = cursor.freeAt().plusSeconds(inbound.orElseThrow().durationSeconds());
    Instant latest = trip.startTs().minusSeconds(outbound.orElseThrow().durationSeconds());
    if (earliest.isBefore(input.horizonStart())) {
      earliest = input.horizonStart();
    }
    if (latest.isAfter(input.horizonEnd())) {
      latest = input.horizonEnd();
    }
    if (!earliest.isBefore(latest)) {
      return best;
    }
    for (Instant seed : SessionCatalog.seeds(input, hub, earliest, latest)) {
      best =
          fromSeed(
              cursor,
              input,
              trip,
              floorPercent,
              plugs,
              hub,
              charger,
              inbound.orElseThrow(),
              earliest,
              latest,
              seed,
              best);
    }
    return best;
  }

  private static Optional<ChargingSession> fromSeed(
      CabCursor cursor,
      PlannerInput input,
      Trip trip,
      int floorPercent,
      PlugCalendar plugs,
      Hub hub,
      Charger charger,
      DistanceDuration inbound,
      Instant earliest,
      Instant latest,
      Instant seed,
      Optional<ChargingSession> best) {
    Instant start = seed.isBefore(earliest) ? earliest : seed;
    for (int attempt = 0; attempt < SolverConfig.FLOW_RETRY_ATTEMPTS; attempt++) {
      if (!start.isBefore(latest)) {
        return best;
      }
      Attempt placed =
          place(cursor, input, trip, floorPercent, plugs, hub, charger, inbound, start, latest);
      if (placed.session().isPresent()) {
        return prefer(best, placed.session().orElseThrow());
      }
      if (placed.retry().isEmpty() || !placed.retry().orElseThrow().isAfter(start)) {
        return best;
      }
      start = placed.retry().orElseThrow();
    }
    return best;
  }

  private static Attempt place(
      CabCursor cursor,
      PlannerInput input,
      Trip trip,
      int floorPercent,
      PlugCalendar plugs,
      Hub hub,
      Charger charger,
      DistanceDuration inbound,
      Instant start,
      Instant latest) {
    Vehicle vehicle = cursor.vehicle();
    Instant depart = start.minusSeconds(inbound.durationSeconds());
    if (cursor.freeAt().isAfter(depart) || start.isBefore(cursor.freeAt())) {
      return Attempt.stop();
    }
    long deadheadWh = RoutePhysics.energy(input, vehicle, inbound.distanceMeters(), start);
    long floorWh = vehicle.calculateFloorSocWh(floorPercent);
    if (deadheadWh > cursor.socWh() || cursor.socWh() - deadheadWh < floorWh) {
      return Attempt.stop();
    }
    long arrivalWh = cursor.socWh() - deadheadWh;
    int powerKw = charger.calculateEffectivePowerKw(vehicle.maxChargeKw());
    Optional<ChargeMath.Need> need =
        ChargeMath.neededAtHub(input, vehicle, hub, trip, floorPercent);
    if (need.isEmpty()) {
      return Attempt.stop();
    }
    Optional<ChargeMath.Quote> quote =
        ChargeMath.fit(
            input.chargingCurve(),
            arrivalWh,
            need.orElseThrow().socWh(),
            vehicle.batteryCapacityWh(),
            powerKw);
    if (quote.isEmpty()) {
      return Attempt.stop();
    }
    Instant end = start.plusSeconds(quote.orElseThrow().durationSeconds());
    if (end.isAfter(latest)) {
      return Attempt.stop();
    }
    if (!charger.isAvailableDuring(start, end)) {
      return Attempt.retry(SessionCatalog.afterOutage(charger, start, end));
    }
    if (!plugs.fits(hub, charger, start, end)) {
      return Attempt.retry(plugs.retryAfter(hub, charger, start, end));
    }
    return booked(
        cursor,
        input,
        trip,
        floorPercent,
        hub,
        charger,
        start,
        end,
        arrivalWh,
        quote.orElseThrow());
  }

  private static Attempt booked(
      CabCursor cursor,
      PlannerInput input,
      Trip trip,
      int floorPercent,
      Hub hub,
      Charger charger,
      Instant start,
      Instant end,
      long arrivalWh,
      ChargeMath.Quote quote) {
    AppendProbe tripProbe =
        TripPlacement.offer(
            cursor.vehicle(), hub.location(), quote.targetSocWh(), end, input, trip, floorPercent);
    if (!tripProbe.feasible()) {
      return Attempt.stop();
    }
    long energyWh = quote.targetSocWh() - arrivalWh;
    ChargingSession session =
        new ChargingSession(
            cursor.vehicleId() + "-" + start.getEpochSecond(),
            cursor.vehicle().id(),
            hub.id(),
            charger.id(),
            start,
            end,
            arrivalWh,
            quote.targetSocWh(),
            ChargePrice.micros(input, hub.id(), start, end, energyWh));
    return Attempt.placed(session);
  }

  private static boolean samePlace(
      CabCursor cursor,
      PlannerInput input,
      Trip trip,
      ChargingSession current,
      int floorPercent,
      PlugCalendar plugs) {
    Optional<Hub> hub = SessionCatalog.hub(input, current);
    Optional<Charger> charger = SessionCatalog.charger(input, current);
    if (hub.isEmpty() || charger.isEmpty()) {
      return false;
    }
    Optional<DistanceDuration> inbound =
        RoutePhysics.travel(input, cursor.location(), hub.orElseThrow().location());
    if (inbound.isEmpty()) {
      return false;
    }
    Instant latest = input.horizonEnd();
    Attempt placed =
        place(
            cursor,
            input,
            trip,
            floorPercent,
            plugs,
            hub.orElseThrow(),
            charger.orElseThrow(),
            inbound.orElseThrow(),
            current.startTime(),
            latest);
    return placed.session().isPresent()
        && placed.session().orElseThrow().startTime().equals(current.startTime())
        && placed.session().orElseThrow().chargerId().equals(current.chargerId());
  }

  private static Optional<ChargingSession> prefer(
      Optional<ChargingSession> incumbent, ChargingSession candidate) {
    if (incumbent.isEmpty() || better(candidate, incumbent.orElseThrow())) {
      return Optional.of(candidate);
    }
    return incumbent;
  }

  private static boolean better(ChargingSession candidate, ChargingSession incumbent) {
    int byCost = Long.compare(candidate.costMicros(), incumbent.costMicros());
    if (byCost != 0) {
      return byCost < 0;
    }
    int byStart = candidate.startTime().compareTo(incumbent.startTime());
    if (byStart != 0) {
      return byStart < 0;
    }
    int byHub = candidate.hubId().value().compareTo(incumbent.hubId().value());
    if (byHub != 0) {
      return byHub < 0;
    }
    return candidate.chargerId().value().compareTo(incumbent.chargerId().value()) < 0;
  }

  private record Attempt(Optional<ChargingSession> session, Optional<Instant> retry) {

    static Attempt placed(ChargingSession session) {
      return new Attempt(Optional.of(session), Optional.empty());
    }

    static Attempt retry(Optional<Instant> when) {
      return new Attempt(Optional.empty(), when);
    }

    static Attempt stop() {
      return new Attempt(Optional.empty(), Optional.empty());
    }
  }
}
