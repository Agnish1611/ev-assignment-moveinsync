package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.OutageWindow;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Inserts one hub session before a trip when a direct append fails on energy or reserve.
 *
 * <p>Time O(H · P · S) for H hubs, P plugs, and S slot attempts. Space O(H + P). The session start
 * SoC is the cab SoC after the deadhead to the hub, and the target is the energy the curve delivers
 * in the booked duration.
 */
final class ChargeStop {

  private static final int SLOT_ATTEMPTS = 6;

  static AppendProbe recover(
      CabCursor cursor,
      PlannerInput input,
      Trip trip,
      int floorPercent,
      PlugCalendar plugs,
      AppendProbe direct) {
    Optional<AppendProbe> chosen = search(cursor, input, trip, floorPercent, plugs);
    if (chosen.isPresent()) {
      return chosen.orElseThrow();
    }
    if (search(cursor, input, trip, floorPercent, new PlugCalendar()).isPresent()) {
      return AppendProbe.blocked(AppendProbe.Block.CONGESTED);
    }
    return direct;
  }

  private static Optional<AppendProbe> search(
      CabCursor cursor, PlannerInput input, Trip trip, int floorPercent, PlugCalendar plugs) {
    Optional<AppendProbe> best = Optional.empty();
    for (Hub hub : sortedHubs(input)) {
      Optional<AppendProbe> candidate = tryHub(cursor, input, trip, floorPercent, plugs, hub);
      if (candidate.isPresent()
          && (best.isEmpty() || better(candidate.orElseThrow(), best.orElseThrow()))) {
        best = candidate;
      }
    }
    return best;
  }

  private static Optional<AppendProbe> tryHub(
      CabCursor cursor,
      PlannerInput input,
      Trip trip,
      int floorPercent,
      PlugCalendar plugs,
      Hub hub) {
    Optional<ChargeMath.Need> need =
        ChargeMath.neededAtHub(input, cursor.vehicle(), hub, trip, floorPercent);
    if (need.isEmpty()) {
      return Optional.empty();
    }
    Optional<DistanceDuration> inbound =
        RoutePhysics.travel(input, cursor.location(), hub.location());
    if (inbound.isEmpty()) {
      return Optional.empty();
    }
    Optional<AppendProbe> best = Optional.empty();
    for (Charger charger : chargersAt(input, hub)) {
      Optional<AppendProbe> candidate =
          tryCharger(
              cursor,
              input,
              trip,
              floorPercent,
              plugs,
              hub,
              charger,
              need.orElseThrow(),
              inbound.orElseThrow());
      if (candidate.isPresent()
          && (best.isEmpty() || better(candidate.orElseThrow(), best.orElseThrow()))) {
        best = candidate;
      }
    }
    return best;
  }

  private static Optional<AppendProbe> tryCharger(
      CabCursor cursor,
      PlannerInput input,
      Trip trip,
      int floorPercent,
      PlugCalendar plugs,
      Hub hub,
      Charger charger,
      ChargeMath.Need need,
      DistanceDuration inbound) {
    if (!charger.status().isAvailable()) {
      return Optional.empty();
    }
    Instant earliest = cursor.freeAt().plusSeconds(inbound.durationSeconds());
    if (earliest.isBefore(input.horizonStart())) {
      earliest = input.horizonStart();
    }
    Instant latest = trip.startTs().minusSeconds(need.toPickup().durationSeconds());
    if (latest.isAfter(input.horizonEnd())) {
      latest = input.horizonEnd();
    }
    return slots(
        cursor, input, trip, floorPercent, plugs, hub, charger, need, inbound, earliest, latest);
  }

  private static Optional<AppendProbe> slots(
      CabCursor cursor,
      PlannerInput input,
      Trip trip,
      int floorPercent,
      PlugCalendar plugs,
      Hub hub,
      Charger charger,
      ChargeMath.Need need,
      DistanceDuration inbound,
      Instant earliest,
      Instant latest) {
    Instant start = earliest;
    for (int attempt = 0; attempt < SLOT_ATTEMPTS; attempt++) {
      if (!start.isBefore(latest)) {
        return Optional.empty();
      }
      Optional<ChargeMath.Quote> quote =
          quote(cursor, input, inbound, need, charger, start, floorPercent);
      if (quote.isEmpty()) {
        return Optional.empty();
      }
      Instant end = start.plusSeconds(quote.orElseThrow().durationSeconds());
      if (end.isAfter(latest)) {
        return Optional.empty();
      }
      if (!charger.isAvailableDuring(start, end)) {
        Optional<Instant> jumped = afterOutage(charger, start, end);
        if (jumped.isEmpty() || !jumped.orElseThrow().isAfter(start)) {
          return Optional.empty();
        }
        start = jumped.orElseThrow();
        continue;
      }
      if (!plugs.fits(hub, charger, start, end)) {
        Optional<Instant> retry = plugs.retryAfter(hub, charger, start, end);
        if (retry.isEmpty() || !retry.orElseThrow().isAfter(start)) {
          return Optional.empty();
        }
        start = retry.orElseThrow();
        continue;
      }
      long deadheadWh =
          RoutePhysics.energy(input, cursor.vehicle(), inbound.distanceMeters(), start);
      long arrivalWh = cursor.socWh() - deadheadWh;
      Optional<AppendProbe> placed =
          assemble(
              cursor,
              input,
              trip,
              floorPercent,
              hub,
              charger,
              start,
              end,
              arrivalWh,
              quote.orElseThrow(),
              deadheadWh);
      if (placed.isPresent()) {
        return placed;
      }
      return Optional.empty();
    }
    return Optional.empty();
  }

  private static Optional<ChargeMath.Quote> quote(
      CabCursor cursor,
      PlannerInput input,
      DistanceDuration inbound,
      ChargeMath.Need need,
      Charger charger,
      Instant start,
      int floorPercent) {
    Vehicle vehicle = cursor.vehicle();
    long deadheadWh = RoutePhysics.energy(input, vehicle, inbound.distanceMeters(), start);
    long floorWh = vehicle.calculateFloorSocWh(floorPercent);
    if (deadheadWh > cursor.socWh() || cursor.socWh() - deadheadWh < floorWh) {
      return Optional.empty();
    }
    long arrivalWh = cursor.socWh() - deadheadWh;
    int powerKw = charger.calculateEffectivePowerKw(vehicle.maxChargeKw());
    return ChargeMath.fit(
        input.chargingCurve(), arrivalWh, need.socWh(), vehicle.batteryCapacityWh(), powerKw);
  }

  private static Optional<AppendProbe> assemble(
      CabCursor cursor,
      PlannerInput input,
      Trip trip,
      int floorPercent,
      Hub hub,
      Charger charger,
      Instant start,
      Instant end,
      long arrivalWh,
      ChargeMath.Quote quote,
      long inboundWh) {
    ChargingSession session =
        new ChargingSession(
            cursor.vehicle().id().value() + "-" + start.getEpochSecond(),
            cursor.vehicle().id(),
            hub.id(),
            charger.id(),
            start,
            end,
            arrivalWh,
            quote.targetSocWh(),
            ChargePrice.micros(input, hub.id(), start, end, quote.targetSocWh() - arrivalWh));
    AppendProbe tripProbe =
        TripPlacement.offer(
            cursor.vehicle(), hub.location(), quote.targetSocWh(), end, input, trip, floorPercent);
    if (!tripProbe.feasible()) {
      return Optional.empty();
    }
    long energy = Math.addExact(tripProbe.energyWh(), quote.targetSocWh() - arrivalWh);
    energy = Math.addExact(energy, inboundWh);
    return Optional.of(AppendProbe.charged(session, tripProbe.assignment().orElseThrow(), energy));
  }

  private static Optional<Instant> afterOutage(Charger charger, Instant start, Instant end) {
    Instant next = start;
    for (OutageWindow outage : charger.outageWindows()) {
      if (outage.overlaps(start, end) && outage.endExclusive().isAfter(next)) {
        next = outage.endExclusive();
      }
    }
    if (next.equals(start)) {
      return Optional.empty();
    }
    return Optional.of(next);
  }

  private static boolean better(AppendProbe candidate, AppendProbe incumbent) {
    int byEnergy = Long.compare(candidate.energyWh(), incumbent.energyWh());
    if (byEnergy != 0) {
      return byEnergy < 0;
    }
    ChargingSession left = candidate.charge().orElseThrow();
    ChargingSession right = incumbent.charge().orElseThrow();
    int byStart = left.startTime().compareTo(right.startTime());
    if (byStart != 0) {
      return byStart < 0;
    }
    return left.chargerId().value().compareTo(right.chargerId().value()) < 0;
  }

  private static List<Hub> sortedHubs(PlannerInput input) {
    List<Hub> hubs = new ArrayList<>(input.hubs());
    hubs.sort(Comparator.comparing(hub -> hub.id().value()));
    return hubs;
  }

  private static List<Charger> chargersAt(PlannerInput input, Hub hub) {
    List<Charger> chargers = new ArrayList<>();
    for (Charger charger : input.chargers()) {
      if (charger.hubId().equals(hub.id())) {
        chargers.add(charger);
      }
    }
    chargers.sort(Comparator.comparing(charger -> charger.id().value()));
    return chargers;
  }
}
