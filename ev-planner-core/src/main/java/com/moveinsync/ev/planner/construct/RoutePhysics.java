package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.util.Optional;

/**
 * Integer travel, energy, and reserve checks shared by construction and repair.
 *
 * <p>Waits between activities do not drain SoC. The validator's energy walk does not apply idle
 * power either, so a claim that did would not match.
 */
final class RoutePhysics {

  static Optional<DistanceDuration> travel(PlannerInput input, Location from, Location to) {
    if (from.id().equals(to.id())) {
      return Optional.of(DistanceDuration.zero());
    }
    return input.distanceProvider().findDistanceAndDuration(from, to);
  }

  static long energy(PlannerInput input, Vehicle vehicle, int meters, Instant time) {
    if (meters <= 0) {
      return 0L;
    }
    return input.consumptionModel().calculateEnergyWh(vehicle, meters, time, input.acOn());
  }

  static boolean canReserve(
      PlannerInput input,
      Vehicle vehicle,
      Location dropoff,
      long socWh,
      Instant when,
      long floorWh) {
    if (input.hubs().isEmpty() || socWh < floorWh) {
      return false;
    }
    for (Hub hub : input.hubs()) {
      if (reaches(input, vehicle, dropoff, hub.location(), socWh, when, floorWh)) {
        return true;
      }
    }
    return false;
  }

  /** True when a full battery at the pickup can finish the trip and still reach a hub. */
  static boolean fullBatteryCanServe(
      PlannerInput input, Vehicle vehicle, Trip trip, int floorPercent) {
    long loaded = energy(input, vehicle, trip.distanceMeters(), trip.startTs());
    long capacity = vehicle.batteryCapacityWh();
    if (loaded > capacity) {
      return false;
    }
    long after = capacity - loaded;
    long floorWh = vehicle.calculateFloorSocWh(floorPercent);
    if (after < floorWh) {
      return false;
    }
    return canReserve(input, vehicle, trip.toLocation(), after, trip.plannedDropoffTs(), floorWh);
  }

  private static boolean reaches(
      PlannerInput input,
      Vehicle vehicle,
      Location from,
      Location hub,
      long socWh,
      Instant when,
      long floorWh) {
    Optional<DistanceDuration> leg = travel(input, from, hub);
    if (leg.isEmpty()) {
      return false;
    }
    long cost = energy(input, vehicle, leg.orElseThrow().distanceMeters(), when);
    return socWh >= cost && socWh - cost >= floorWh;
  }
}
