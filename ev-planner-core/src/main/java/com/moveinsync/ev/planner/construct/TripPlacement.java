package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.util.Optional;

/**
 * Direct append of one trip from a cab's current location, charge, and free time. Deadhead duration
 * is stored as 0 seconds (D19).
 */
final class TripPlacement {

  static AppendProbe offer(
      Vehicle vehicle,
      Location location,
      long socWh,
      Instant freeAt,
      PlannerInput input,
      Trip trip,
      int floorPercent) {
    Optional<DistanceDuration> leg = RoutePhysics.travel(input, location, trip.fromLocation());
    if (leg.isEmpty()) {
      return AppendProbe.blocked(AppendProbe.Block.ROUTE);
    }
    DistanceDuration travel = leg.orElseThrow();
    Instant departBy = trip.startTs().minusSeconds(travel.durationSeconds());
    if (freeAt.isAfter(departBy)) {
      return AppendProbe.blocked(AppendProbe.Block.TIME);
    }
    return place(vehicle, socWh, input, trip, floorPercent, travel);
  }

  private static AppendProbe place(
      Vehicle vehicle,
      long socWh,
      PlannerInput input,
      Trip trip,
      int floorPercent,
      DistanceDuration travel) {
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
    TripAssignment assignment =
        new TripAssignment(
            trip,
            vehicle.id(),
            trip.startTs(),
            trip.plannedDropoffTs(),
            travel.distanceMeters(),
            0,
            deadheadWh,
            arrivalWh,
            dropoffWh);
    return AppendProbe.placed(assignment, Math.addExact(deadheadWh, loadedWh));
  }
}
