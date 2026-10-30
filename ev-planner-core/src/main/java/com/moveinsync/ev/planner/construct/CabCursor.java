package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One cab's route while trips are still being inserted. Appending is the only edit.
 *
 * <p>Deadhead duration on each assignment is 0 seconds (D19). A charge stop, when one is required,
 * is appended before the trip and moves the cab to the hub.
 */
final class CabCursor {

  private final CabShift shift;
  private final List<RouteActivity> activities = new ArrayList<>();
  private Location location;
  private long socWh;
  private Instant freeAt;

  CabCursor(CabShift shift) {
    this.shift = shift;
    this.location = shift.startLocation();
    this.socWh = shift.initialSocWh();
    this.freeAt = shift.availableFrom();
  }

  static List<CabCursor> open(PlannerInput input) {
    List<CabCursor> cursors = new ArrayList<>();
    for (CabShift cab : input.cabs()) {
      cursors.add(new CabCursor(cab));
    }
    return cursors;
  }

  String vehicleId() {
    return shift.vehicle().id().value();
  }

  Vehicle vehicle() {
    return shift.vehicle();
  }

  Location location() {
    return location;
  }

  long socWh() {
    return socWh;
  }

  Instant freeAt() {
    return freeAt;
  }

  AppendProbe offer(PlannerInput input, Trip trip, int floorPercent) {
    return TripPlacement.offer(vehicle(), location, socWh, freeAt, input, trip, floorPercent);
  }

  AppendProbe offerCharged(PlannerInput input, Trip trip, int floorPercent, PlugCalendar plugs) {
    AppendProbe direct = offer(input, trip, floorPercent);
    if (direct.feasible()
        || direct.block() == AppendProbe.Block.ROUTE
        || direct.block() == AppendProbe.Block.TIME) {
      return direct;
    }
    return ChargeStop.recover(this, input, trip, floorPercent, plugs, direct);
  }

  void commit(PlannerInput input, AppendProbe probe, PlugCalendar plugs) {
    if (probe.charge().isPresent()) {
      SessionReplay.adopt(this, input, probe.charge().orElseThrow(), plugs);
    }
    TripAssignment assignment = probe.assignment().orElseThrow();
    push(
        assignment,
        assignment.trip().toLocation(),
        assignment.departureAfterDropoffSocWh(),
        assignment.dropoffTime());
  }

  void push(RouteActivity activity, Location nextLocation, long nextSocWh, Instant nextFree) {
    activities.add(activity);
    location = nextLocation;
    socWh = nextSocWh;
    freeAt = nextFree;
  }

  VehicleSchedule toSchedule() {
    return new VehicleSchedule(
        shift.vehicle(),
        shift.startLocation(),
        shift.initialSocWh(),
        shift.availableFrom(),
        List.copyOf(activities));
  }
}
