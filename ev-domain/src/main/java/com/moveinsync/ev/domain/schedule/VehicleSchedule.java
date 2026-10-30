package com.moveinsync.ev.domain.schedule;

import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable sequence of activities scheduled for a specific vehicle across a shift horizon. */
public final class VehicleSchedule {

  private final Vehicle vehicle;
  private final Location initialLocation;
  private final long initialSocWh;
  private final Instant availableFrom;
  private final List<RouteActivity> activities;

  public VehicleSchedule(
      Vehicle vehicle,
      Location initialLocation,
      long initialSocWh,
      Instant availableFrom,
      List<RouteActivity> activities) {
    this.vehicle = Objects.requireNonNull(vehicle, "vehicle must not be null");
    this.initialLocation =
        Objects.requireNonNull(initialLocation, "initialLocation must not be null");
    this.availableFrom = Objects.requireNonNull(availableFrom, "availableFrom must not be null");
    Objects.requireNonNull(activities, "activities list must not be null");

    List<RouteActivity> sorted = new ArrayList<>(activities);
    sorted.sort(Comparator.comparing(RouteActivity::activityStartTime));

    this.initialSocWh = initialSocWh;
    this.activities = Collections.unmodifiableList(sorted);
  }

  public Vehicle vehicle() {
    return vehicle;
  }

  public VehicleId vehicleId() {
    return vehicle.id();
  }

  public Location initialLocation() {
    return initialLocation;
  }

  public long initialSocWh() {
    return initialSocWh;
  }

  public Instant availableFrom() {
    return availableFrom;
  }

  public List<RouteActivity> activities() {
    return activities;
  }

  public boolean isEmpty() {
    return activities.isEmpty();
  }

  public int activityCount() {
    return activities.size();
  }
}
