package com.moveinsync.ev.planner.input;

import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import java.time.Instant;
import java.util.Objects;

/**
 * Where one cab is, and how much energy it has, at the start of the planning horizon.
 *
 * @param vehicle the cab
 * @param startLocation position at {@code availableFrom}
 * @param initialSocWh stored energy in watt-hours
 * @param availableFrom first instant the cab may be given work, UTC
 */
public record CabShift(
    Vehicle vehicle, Location startLocation, long initialSocWh, Instant availableFrom) {

  public CabShift {
    Objects.requireNonNull(vehicle, "vehicle must not be null");
    Objects.requireNonNull(startLocation, "startLocation must not be null");
    Objects.requireNonNull(availableFrom, "availableFrom must not be null");
    if (initialSocWh < 0) {
      throw new IllegalArgumentException("initialSocWh must be non-negative: " + initialSocWh);
    }
  }
}
