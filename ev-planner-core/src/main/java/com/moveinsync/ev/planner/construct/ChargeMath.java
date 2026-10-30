package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.charging.ChargingCurve;
import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/** Curve and reserve arithmetic for one charge stop. Duration rounds up and energy rounds down. */
final class ChargeMath {

  private static final int CURVE_BUMPS = 8;

  record Quote(long durationSeconds, long targetSocWh) {}

  record Need(long socWh, DistanceDuration toPickup) {}

  static Optional<Need> neededAtHub(
      PlannerInput input, Vehicle vehicle, Hub hub, Trip trip, int floorPercent) {
    Optional<DistanceDuration> toPickup =
        RoutePhysics.travel(input, hub.location(), trip.fromLocation());
    if (toPickup.isEmpty()) {
      return Optional.empty();
    }
    OptionalLong reserve = reserveWh(input, vehicle, trip.toLocation(), trip.plannedDropoffTs());
    if (reserve.isEmpty()) {
      return Optional.empty();
    }
    long floorWh = vehicle.calculateFloorSocWh(floorPercent);
    long loadedWh = RoutePhysics.energy(input, vehicle, trip.distanceMeters(), trip.startTs());
    long deadheadWh =
        RoutePhysics.energy(
            input, vehicle, toPickup.orElseThrow().distanceMeters(), trip.startTs());
    long minDropoff = Math.addExact(floorWh, reserve.getAsLong());
    long minPickup = Math.addExact(minDropoff, loadedWh);
    long minHub = Math.addExact(minPickup, deadheadWh);
    if (minHub > vehicle.batteryCapacityWh()) {
      return Optional.empty();
    }
    return Optional.of(new Need(minHub, toPickup.orElseThrow()));
  }

  /**
   * Duration whose delivered energy is at least {@code neededSocWh} and whose length is at least
   * the curve's own duration for that delivered target.
   */
  static Optional<Quote> fit(
      ChargingCurve curve, long arrivalSocWh, long neededSocWh, long capacityWh, int powerKw) {
    if (neededSocWh > capacityWh) {
      return Optional.empty();
    }
    long desired = neededSocWh;
    if (arrivalSocWh >= neededSocWh) {
      if (arrivalSocWh >= capacityWh) {
        return Optional.empty();
      }
      desired = arrivalSocWh + 1L;
    }
    if (desired <= arrivalSocWh || desired > capacityWh) {
      return Optional.empty();
    }
    long duration = curve.calculateDurationSeconds(arrivalSocWh, desired, capacityWh, powerKw);
    if (duration <= 0L) {
      return Optional.empty();
    }
    long target = curve.calculateTargetSocWh(arrivalSocWh, duration, capacityWh, powerKw);
    for (int bump = 0; bump < CURVE_BUMPS; bump++) {
      if (target < arrivalSocWh) {
        return Optional.empty();
      }
      long required = curve.calculateDurationSeconds(arrivalSocWh, target, capacityWh, powerKw);
      if (target >= desired && duration >= required) {
        return Optional.of(new Quote(duration, target));
      }
      if (target >= capacityWh) {
        return Optional.empty();
      }
      duration++;
      target = curve.calculateTargetSocWh(arrivalSocWh, duration, capacityWh, powerKw);
    }
    return Optional.empty();
  }

  private static OptionalLong reserveWh(
      PlannerInput input, Vehicle vehicle, Location dropoff, Instant when) {
    boolean found = false;
    long best = Long.MAX_VALUE;
    for (Hub hub : input.hubs()) {
      Optional<DistanceDuration> leg = RoutePhysics.travel(input, dropoff, hub.location());
      if (leg.isEmpty()) {
        continue;
      }
      found = true;
      long cost = RoutePhysics.energy(input, vehicle, leg.orElseThrow().distanceMeters(), when);
      if (cost < best) {
        best = cost;
      }
    }
    if (!found) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(best);
  }
}
