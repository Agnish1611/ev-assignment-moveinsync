package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One cab's route while trips are still being inserted. Appending is the only edit: trips are
 * considered in an order that does not require splicing the middle of a route.
 *
 * <p>Deadhead duration on each assignment is 0 seconds (D19). Distance and energy are the real
 * deadhead. The validator starts the activity at pickup minus the claimed duration and also
 * requires the gap before that start to cover the drive, so a non-zero duration would count the
 * drive twice and can start the activity before the horizon.
 */
final class CabCursor {

  private final CabShift shift;
  private final List<TripAssignment> assignments = new ArrayList<>();
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

  AppendProbe offer(PlannerInput input, Trip trip, int floorPercent) {
    Optional<DistanceDuration> leg = RoutePhysics.travel(input, location, trip.fromLocation());
    if (leg.isEmpty()) {
      return AppendProbe.blocked(AppendProbe.Block.ROUTE);
    }
    DistanceDuration travel = leg.orElseThrow();
    Instant departBy = trip.startTs().minusSeconds(travel.durationSeconds());
    if (freeAt.isAfter(departBy)) {
      return AppendProbe.blocked(AppendProbe.Block.TIME);
    }
    return place(input, trip, floorPercent, travel);
  }

  void commit(TripAssignment assignment) {
    assignments.add(assignment);
    location = assignment.trip().toLocation();
    socWh = assignment.departureAfterDropoffSocWh();
    freeAt = assignment.dropoffTime();
  }

  VehicleSchedule toSchedule() {
    return new VehicleSchedule(
        shift.vehicle(),
        shift.startLocation(),
        shift.initialSocWh(),
        shift.availableFrom(),
        List.copyOf(assignments));
  }

  private AppendProbe place(
      PlannerInput input, Trip trip, int floorPercent, DistanceDuration travel) {
    Vehicle vehicle = shift.vehicle();
    long floorWh = vehicle.calculateFloorSocWh(floorPercent);
    long deadheadWh = RoutePhysics.energy(input, vehicle, travel.distanceMeters(), trip.startTs());
    if (deadheadWh > socWh || socWh - deadheadWh < floorWh) {
      return AppendProbe.blocked(AppendProbe.Block.ENERGY);
    }
    long arrivalWh = socWh - deadheadWh;
    long loadedWh = RoutePhysics.energy(input, vehicle, trip.distanceMeters(), trip.startTs());
    if (loadedWh > arrivalWh || arrivalWh - loadedWh < floorWh) {
      return AppendProbe.blocked(AppendProbe.Block.ENERGY);
    }
    long dropoffWh = arrivalWh - loadedWh;
    if (!RoutePhysics.canReserve(
        input, vehicle, trip.toLocation(), dropoffWh, trip.plannedDropoffTs(), floorWh)) {
      return AppendProbe.blocked(AppendProbe.Block.RESERVE);
    }
    int claimedDeadheadSeconds = 0;
    TripAssignment assignment =
        new TripAssignment(
            trip,
            vehicle.id(),
            trip.startTs(),
            trip.plannedDropoffTs(),
            travel.distanceMeters(),
            claimedDeadheadSeconds,
            deadheadWh,
            arrivalWh,
            dropoffWh);
    return AppendProbe.placed(assignment, Math.addExact(deadheadWh, loadedWh));
  }
}
