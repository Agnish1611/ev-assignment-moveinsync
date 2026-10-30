package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerStatus;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.DriftEvent;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Turns one drift event into the fleet snapshot the ladder replays. A failed plug is marked down. A
 * cancelled trip and a removed cab must already be absent from that snapshot.
 */
final class ReplanTrigger {

  private final PlannerInput input;
  private final String emergencyCab;
  private final Set<String> cancelled;
  private final Set<String> reopen;

  private ReplanTrigger(
      PlannerInput input, String emergencyCab, Set<String> cancelled, Set<String> reopen) {
    this.input = input;
    this.emergencyCab = emergencyCab;
    this.cancelled = cancelled;
    this.reopen = reopen;
  }

  static ReplanTrigger prepare(PlannerInput input, FleetPlan current, DriftEvent event) {
    return switch (event.kind()) {
      case BATTERY, LATE_CAB -> cab(input, event.subjectId());
      case CHARGER_FAILURE -> charger(input, current, event);
      case TRIP_ADDED -> added(input, current, event.subjectId());
      case TRIP_CANCELLED -> cancelled(input, current, event.subjectId());
      case CAB_REMOVED -> removed(input, current, event.subjectId());
    };
  }

  PlannerInput input() {
    return input;
  }

  String emergencyCab() {
    return emergencyCab;
  }

  Set<String> cancelled() {
    return cancelled;
  }

  Set<String> reopen() {
    return reopen;
  }

  private static ReplanTrigger cab(PlannerInput input, String cabId) {
    if (!hasCab(input, cabId)) {
      throw new IllegalArgumentException("drift subject is not a cab: " + cabId);
    }
    return plain(input, cabId);
  }

  private static ReplanTrigger added(PlannerInput input, FleetPlan current, String tripId) {
    if (findTrip(input, tripId).isEmpty()) {
      throw new IllegalArgumentException("drift subject is not a trip: " + tripId);
    }
    if (assigned(current, tripId)) {
      return plain(input, "");
    }
    return new ReplanTrigger(input, "", Set.of(), Set.of(tripId));
  }

  private static ReplanTrigger cancelled(PlannerInput input, FleetPlan current, String tripId) {
    if (findTrip(input, tripId).isPresent()) {
      throw new IllegalArgumentException("cancelled trip is still in the planner input");
    }
    if (!assigned(current, tripId) && !parked(current, tripId)) {
      throw new IllegalArgumentException("drift subject is not a trip: " + tripId);
    }
    return new ReplanTrigger(input, "", Set.of(tripId), Set.of());
  }

  private static ReplanTrigger removed(PlannerInput input, FleetPlan current, String cabId) {
    if (hasCab(input, cabId)) {
      throw new IllegalArgumentException("removed cab is still in the planner input");
    }
    if (!hasSchedule(current, cabId)) {
      throw new IllegalArgumentException("drift subject is not a cab: " + cabId);
    }
    return plain(input, "");
  }

  private static ReplanTrigger charger(PlannerInput input, FleetPlan current, DriftEvent event) {
    Charger failed = plug(input, event.subjectId());
    requireReading(input, current, failed, event);
    return plain(without(input, failed), "");
  }

  private static void requireReading(
      PlannerInput input, FleetPlan current, Charger failed, DriftEvent event) {
    if (event.reportedSocWh().isEmpty()) {
      return;
    }
    Optional<ChargingSession> active = active(current, failed.id().value(), event.observedAt());
    if (active.isEmpty()) {
      throw new IllegalArgumentException("reported SoC has no active session");
    }
    String cabId = active.orElseThrow().vehicleId().value();
    CabShift shift = shift(input, cabId);
    if (shift.initialSocWh() != event.reportedSocWh().getAsLong()) {
      throw new IllegalArgumentException("reported SoC does not match the cab");
    }
  }

  private static Optional<ChargingSession> active(FleetPlan plan, String chargerId, Instant at) {
    ChargingSession found = null;
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof ChargingSession session && covers(session, chargerId, at)) {
          if (found != null) {
            throw new IllegalArgumentException("charger has two active sessions");
          }
          found = session;
        }
      }
    }
    return Optional.ofNullable(found);
  }

  private static boolean covers(ChargingSession session, String chargerId, Instant at) {
    return session.chargerId().value().equals(chargerId)
        && !session.startTime().isAfter(at)
        && session.endTime().isAfter(at);
  }

  private static PlannerInput without(PlannerInput input, Charger failed) {
    PlannerInput.Builder builder =
        PlannerInput.builder()
            .tenantId(input.tenantId())
            .horizon(input.horizonStart(), input.horizonEnd())
            .consumptionModel(input.consumptionModel())
            .distanceProvider(input.distanceProvider())
            .chargingCurve(input.chargingCurve())
            .acOn(input.acOn())
            .idlePowerW(input.idlePowerW());
    input.hubs().forEach(builder::addHub);
    for (Charger charger : input.chargers()) {
      builder.addCharger(charger.id().equals(failed.id()) ? down(charger) : charger);
    }
    input.cabs().forEach(builder::addCab);
    input.trips().forEach(builder::addTrip);
    input.tariffs().forEach(builder::addTariff);
    return builder.build();
  }

  private static Charger down(Charger charger) {
    return new Charger(
        charger.tenantId(),
        charger.id(),
        charger.hubId(),
        charger.powerKw(),
        ChargerStatus.FAULTY,
        charger.outageWindows());
  }

  private static ReplanTrigger plain(PlannerInput input, String emergencyCab) {
    return new ReplanTrigger(input, emergencyCab, Set.of(), Set.of());
  }

  private static Charger plug(PlannerInput input, String chargerId) {
    for (Charger charger : input.chargers()) {
      if (charger.id().value().equals(chargerId)) {
        return charger;
      }
    }
    throw new IllegalArgumentException("drift subject is not a charger: " + chargerId);
  }

  private static CabShift shift(PlannerInput input, String cabId) {
    for (CabShift cab : input.cabs()) {
      if (cab.vehicle().id().value().equals(cabId)) {
        return cab;
      }
    }
    throw new IllegalArgumentException("active session cab is not in the planner input");
  }

  private static boolean hasCab(PlannerInput input, String cabId) {
    for (CabShift cab : input.cabs()) {
      if (cab.vehicle().id().value().equals(cabId)) {
        return true;
      }
    }
    return false;
  }

  private static Optional<Trip> findTrip(PlannerInput input, String tripId) {
    for (Trip trip : input.trips()) {
      if (trip.id().value().equals(tripId)) {
        return Optional.of(trip);
      }
    }
    return Optional.empty();
  }

  private static boolean assigned(FleetPlan plan, String tripId) {
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof TripAssignment assignment
            && assignment.trip().id().value().equals(tripId)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean parked(FleetPlan plan, String tripId) {
    return plan.unassignedTrips().stream().anyMatch(row -> row.trip().id().value().equals(tripId));
  }

  private static boolean hasSchedule(FleetPlan plan, String cabId) {
    return plan.vehicleSchedules().stream().anyMatch(row -> row.vehicleId().value().equals(cabId));
  }
}
